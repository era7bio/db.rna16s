/*
  # Drop inconsistent assignments

  Here we want to drop *inconsistent* assignments from our database. The idea here is to determine it based on sequences equivalence classes (1) (the we got from the previous clustering step) and their assignments taxonomic similarity (2).

  1. First we take the sequence clusters and for each of them (`C`) construct a common set of assignments (using the map we have from the previous steps): `Taxa(C)`.

  2. Then we consider each assignment `T` (from the old map) and decide wheter to drop it or to keep by comparing _its parent's_ cumulative count `Count(Parent(T))` with the total count (which is the size of `Taxa(C)`). If it's over some fixed threshold, we keep it, otherwise drop.
*/
package ohnosequences.db.rna16s.test

import ohnosequences.db._, csvUtils._, collectionUtils._
import ohnosequences.ncbitaxonomy._, titan._
import ohnosequences.fastarious.fasta._
import ohnosequences.statika._
import ohnosequences.mg7._
import ohnosequences.awstools.s3._
import com.amazonaws.auth._
import com.amazonaws.services.s3.transfer._
import ohnosequences.blast.api._, outputFields._
import com.github.tototoshi.csv._
import better.files._
import com.bio4j.titan.model.ncbiTaxonomy.TitanNCBITaxonomyGraph

case class inconsistentAssignmentsFilter(
  val taxonomyGraph: TitanNCBITaxonomyGraph,
  val assignmentsTable: File,
  /* This threshold determines minimum ratio of the taxon's parent's count compared to the total count */
  val minimumRatio: Double = 0.75, // 75%
  /* This determines how many levels up we're going to take the ancestor to check the counts ratio */
  val ancestryLevel: Int = 2 // grandparent
) {
  type Lineage = Seq[Taxon]

  /* Mapping of sequence IDs to the list of their taxonomic assignments */
  lazy val referenceMap: Map[ID, Set[Taxon]] =
    CSVReader.open(assignmentsTable.toJava)(csvUtils.UnixCSVFormat).iterator
      .foldLeft(Map[ID, Set[Taxon]]()) { (acc, row) =>
        acc.updated(
          row(0),
          row(1).split(';').map(_.trim).toSet
        )
      }

  def referenceTaxaFor(id: ID): Set[Taxon] = referenceMap.get(id).getOrElse(Set())

  /* This method for a given sequence of taxons (with repeats) returns their cumulative counts and lineages */
  // TODO: this should be a piece of reusable code in MG7
  def getAccumulatedCounts(taxa: Seq[Taxon]): Map[Taxon, (Int, Lineage)] = {
    // NOTE: this mutable map is used in getLineage for memoization of the results that we get from the DB
    val cacheMap: scala.collection.mutable.Map[Taxon, Lineage] = scala.collection.mutable.Map()

    /* This method looks up the lineage in the cache or queries the DB and updates the cache */
    def getLineage(id: Taxon): Lineage = cacheMap.get(id).getOrElse {
      val lineage = taxonomyGraph.getTaxon(id)
        .map{ _.ancestors }.getOrElse( Seq() )
        .map{ _.id }
      cacheMap.update(id, lineage)
      lineage
    }

    val counter = loquats.countDataProcessing()

    val directMap      = counter.directCounts(taxa, getLineage)
    val accumulatedMap = counter.accumulatedCounts(directMap, getLineage)

    accumulatedMap
  }

  /* This predicate determines whether to drop the taxon or to keep it */
  def predicate(countsMap: Map[Taxon, (Int, Lineage)], totalCount: Int): Taxon => Boolean = { taxon =>

    countsMap.get(taxon)
      /* First we find `taxon`'s ancestor ID */
      .flatMap { case (_, lineage) => lineage.reverse.drop(ancestryLevel + 1).headOption }
      /* then we find out its count */
      .flatMap { ancestorId => countsMap.get(ancestorId) }
      /* and compare it with the total: it has to be more than `minimumRatio` for `taxon` to pass */
      .map { case (ancestorCount, _) =>
        ((ancestorCount: Double) / totalCount) >= minimumRatio
      }
      .getOrElse(false)
  }

  def partitionAssignments(cluster: Seq[ID]): Seq[(ID, Set[Taxon], Set[Taxon])] = {

    /* Corresponding taxa (to all sequence in the cluster together) */
    val taxa: Seq[Taxon] = cluster.flatMap { id => referenceTaxaFor(id) }
    val totalCount = taxa.length

    /* Counts (and lineages) for the taxa */
    val accumulatedCountsMap: Map[Taxon, (Int, Lineage)] = getAccumulatedCounts(taxa)

    /* Checking each assignment of the query sequence */
    cluster.map { id =>

      val (acceptedTaxa, rejectedTaxa) = referenceTaxaFor(id).partition(
        predicate(accumulatedCountsMap, totalCount)
      )

      /* Among previously rejected we pick those that are descendants of the accepted taxa and keep them too */
      val (acceptedDescendants, rejectedRest) = rejectedTaxa.partition { taxon =>
        accumulatedCountsMap.get(taxon)
          .map { case (_, lineage) =>
            // at least one ancestor is among accepted ones:
            (lineage.toSet intersect acceptedTaxa).nonEmpty
          }
          .getOrElse(false)
      }

      (id, acceptedTaxa ++ acceptedDescendants, rejectedRest)
    }
  }

}

case object dropInconsistentAssignments extends FilterDataFrom(dropRedundantAssignments)(
  deps = clusteringResults, ncbiTaxonomyBundle
) {

  /* Mapping of sequence IDs to corresponding FASTA sequences */
  lazy val id2fasta: Map[ID, Fasta] = source.fasta.stream
    .foldLeft(Map[ID, Fasta]()) { (acc, fasta) =>
      acc.updated(
        fasta.getV(header).id,
        fasta
      )
    }

  lazy val filter = inconsistentAssignmentsFilter(
    ncbiTaxonomyBundle.graph,
    source.table.file
  )

  def filterData(): Unit = clusteringResults.clusters.lines
    .flatMap { line => filter.partitionAssignments( line.split(',') ) }
    .foreach { case (id, accepted, rejected) =>

      writeOutput(id, accepted.toSeq, rejected.toSeq, id2fasta(id))
    }
}

case object dropInconsistentAssignmentsAndGenerate extends FilterAndGenerateBlastDB(
  ohnosequences.db.rna16s.dbName,
  ohnosequences.db.rna16s.dbType,
  ohnosequences.db.rna16s.test.dropInconsistentAssignments
)


/* This object provides some context for further testing in REPL */
case object inconsistentAssignmentsTest {

  import ohnosequencesBundles.statika._
  import com.thinkaurelius.titan.core.TitanFactory
  import com.bio4j.titan.util.DefaultTitanGraph
  import org.apache.commons.configuration.Configuration

  // You need to download some files for testing
  case object local {

    lazy val configuration: Configuration = DefaultBio4jTitanConfig(file"data/in/bio4j-taxonomy-titandb".toJava)

    lazy val taxonomyGraph =
      new TitanNCBITaxonomyGraph(
        new DefaultTitanGraph(TitanFactory.open(configuration))
      )

    val assignmentsTable = file"data/in/table.csv"
  }

  // val testCluster: Seq[ID] = file"data/in/URS00008E71FD-cluster.csv".lines.next.split(',')

  val filter = inconsistentAssignmentsFilter(
    local.taxonomyGraph,
    local.assignmentsTable
  )
}
