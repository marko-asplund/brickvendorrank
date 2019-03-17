package com.practicingtechie

import cats.effect._
import cats.implicits._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.log4s.getLogger
import org.rogach.scallop._

import scala.language.higherKinds

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val partCodesFile = opt[String](required = true)
  val inventoryFile = opt[String]()
  val fetchInventory = opt[Boolean](default = Some(false))

  verify()
}

object InventoryFetcher {
  val BrickLinkBaseUrl = "https://www.bricklink.com"
  val PageSize = 500
  val MaxSellers = 50
  val InventoryOutputFilePrefix = "brick-inventory-"
  val VendorsOutputFilePrefix = "brick-vendors-"
  val OutputFileSuffix = ".tsv"

  case class ItemResponse(result: ItemResult)
  case class ItemResult(typeList: List[ItemTypeObj])
  case class ItemTypeObj(`type`: String, items: List[Item])
  case class Item(idItem: Int, strItemNo: String, strItemName: String, strPCC: String)

  case class InvResponse(list: List[InvItem])
  case class InvItem(strStorename: String, strSellerCountryCode: String, n4SellerFeedbackScore: Int)
}

class InventoryFetcher[F[_]: ConcurrentEffect](val hc: Client[F]) extends Http4sClientDsl[F] {
  import InventoryFetcher._
  import cats.data.EitherT
  import io.circe.generic.auto._
  import org.http4s.circe.CirceEntityDecoder._

  val logger = getLogger

  def getItem(partCode: String) = {
    val url = s"$BrickLinkBaseUrl/ajax/clone/search/searchproduct.ajax?q=${partCode}&st=0&brand=1000&yf=0&yt=0&reg=0&ca=0&nmp=0&color=-1&min=0&max=0&minqty=0&nosuperlot=1&incomplete=0&showempty=1&rpp=25&pi=1&ci=0"
    hc.expect[ItemResponse](url) map { r =>
      r.result.typeList.headOption.flatMap(_.items.headOption) match {
        case i @ Some(id) =>
          logger.info(s"part mapping: $partCode ==> $id")
          i
        case i =>
          logger.warn(s"part mapping: no mapping for $partCode")
          i
      }
    }
  }

  def getItemSellers(itemId: Int) = {
    val url = s"$BrickLinkBaseUrl/ajax/clone/catalogifs.ajax?itemid=${itemId}&iconly=0&rpp=${PageSize}"
    hc.expect[InvResponse](url) map { r =>
      r.list.map(_.strStorename).toSet.toList
    }
  }

  def getPartSellersForPart(partCode: String) =
    (for {
      item <- EitherT.fromOptionF(getItem(partCode), s"no item found for partcode $partCode")
      sellers <- EitherT.right[String](getItemSellers(item.idItem))
    } yield sellers).value

  def sortSellers(partCodes: List[String]) = {
    partCodes.map(code => getPartSellersForPart(code).map(r => code -> r)).traverse(a => a).map { sellersByCode =>
      sellersByCode.collect { case (code, Right(sellers)) => sellers.map(s => s -> code) }.
        flatten.groupBy { case (seller, code) => seller }.
        map { case (k, v) => k -> v.map(i => i._2).toSet[String].toList.sortBy(_.toInt) }.
        toList.sortBy(-_._2.size)
    }
  }
}

object Searcher extends IOApp {
  import InventoryFetcher._
  import cats.effect._
  import fs2.Stream
  import org.http4s.client.blaze._
  import scala.concurrent.ExecutionContext.global
  import scala.io.Source
  import scala.annotation.tailrec


  val logger = getLogger

  val TestPartCodes = List("6013711", "4211045", "4570027", "4566256")

  def httpClient() =
    BlazeClientBuilder[IO](global).stream.map(org.http4s.client.middleware.Logger(true, false))

  def writeRowsToFile(outputFilePrefix: String, header: Option[String], rows: List[String]): Unit = {
    import java.io.File
    val outputFile = File.createTempFile(outputFilePrefix, OutputFileSuffix, new File("."))
    val pw = new java.io.PrintWriter(outputFile)
    header.foreach(h => pw.write(s"$h\n"))
    pw.write(rows.mkString("\n"))
    pw.close
    logger.info(s"*** data written to file $outputFile ***")
  }

  def readPartCodes(fn: String): List[String] =
    Source.fromFile(fn).getLines.toList.map(_.split("\t").head)

  def readInventory(fn: String): List[(String, Set[String])] =
    Source.fromFile(fn).getLines.toList.drop(1).map { l =>
      l.split("\t").toList match {
        case vendor :: parts :: Nil => vendor -> parts.replaceAll(" ", "").split(",").toSet
      }
    }

  override def run(args: List[String]): IO[ExitCode] = {
    val conf = new Conf(args)
    println(conf.summary)

    case class InventoryEntry(vendor: String, availableParts: Set[String], partsToBuy: Set[String])

    if (conf.fetchInventory()) {
      val codes = readPartCodes(conf.partCodesFile())
      (for {
        hc <- httpClient()
        searcher = new InventoryFetcher[IO](hc)
        partsBySeller <- Stream.eval(searcher.sortSellers(codes))
        rows = partsBySeller.map{ case (seller, parts) => s"$seller\t${parts.mkString(", ")}" }
        _ <- Stream.eval(IO(writeRowsToFile(InventoryOutputFilePrefix, Some("vendor\tavailable parts"), rows)))
      } yield partsBySeller).compile.drain.as(ExitCode.Success)
    } else {
      require(conf.inventoryFile.isSupplied, "inventory file must be supplied")
      val requiredParts = readPartCodes(conf.partCodesFile()).toSet
      val inventory = readInventory(conf.inventoryFile())

      def findTopSellersForRequiredParts() = {
        @tailrec
        def go(i: Int, missingParts: Set[String], acc: Seq[InventoryEntry]): Seq[InventoryEntry] = {
          logger.debug(s"go: $i ${missingParts.size}")
          if (i < 10 && missingParts.size > 0) {
            val foundParts = inventory.map { case (_, availableParts) => availableParts.intersect(missingParts) }
            val topIndex = inventory.zipWithIndex.
              map { case ((_, idx)) => idx -> foundParts(idx).size }.
              sortBy(-_._2).head._1
            val newMissingParts = missingParts.diff(inventory(topIndex)._2)
            println(s"missing: ==> $missingParts")
            if (newMissingParts.size < missingParts.size)
              go(i + 1, newMissingParts, acc :+
                InventoryEntry(inventory(topIndex)._1, inventory(topIndex)._2, foundParts(topIndex)))
            else acc
          } else acc
        }
        go(0, requiredParts, Seq.empty)
      }
      val sellers = findTopSellersForRequiredParts().toList
      val rows = sellers.map { i =>
        def sortedList(s: Set[String]) = s.toList.sortBy(_.toInt).mkString(",")
        val missing = requiredParts.diff(i.availableParts)
        s"${i.vendor}\t${sortedList(i.availableParts)}\t${sortedList(missing)}\t${sortedList(i.partsToBuy)}"
      }
      writeRowsToFile(VendorsOutputFilePrefix, Some("vendor\tavailable parts\tmissing parts\tparts to buy"), rows)

      ExitCode.Success.pure[IO]
    }
  }

}
