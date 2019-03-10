package com.practicingtechie

import cats.effect._
import cats.implicits._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.log4s.getLogger

import scala.language.higherKinds


object LegoSearcher {
  val BrickLinkBaseUrl = "https://www.bricklink.com"
  val PageSize = 500
  val MaxSellers = 50
  val OutputFilePrefix = "brickvendors-"
  val OutputFileSuffix = ".tsv"

  case class ItemResponse(result: ItemResult)
  case class ItemResult(typeList: List[ItemTypeObj])
  case class ItemTypeObj(`type`: String, items: List[Item])
  case class Item(idItem: Int, strItemNo: String, strItemName: String, strPCC: String)

  case class InvResponse(list: List[InvItem])
  case class InvItem(strStorename: String, strSellerCountryCode: String, n4SellerFeedbackScore: Int)
}

class LegoSearcher[F[_]: ConcurrentEffect](val hc: Client[F]) extends Http4sClientDsl[F] {
  import LegoSearcher._
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

  def rankSellers(partCodes: List[String]) = {
    val partCodesSet = partCodes.toSet
    partCodes.map(code => getPartSellersForPart(code).map(r => code -> r)).traverse(a => a).map { sellersByCode =>
      sellersByCode.collect { case (code, Right(sellers)) => sellers.map(s => s -> code) }.
        flatten.groupBy { case (seller, code) => seller }.
        map { case (k, v) => k -> partCodesSet.diff(v.map(_._2).toSet).toList.sortBy(_.toInt) }.
        toList.sortBy(_._2.size).take(MaxSellers)
    }
  }
}

object Searcher extends IOApp {
  import LegoSearcher._
  import cats.effect._
  import fs2.Stream
  import org.http4s.client.blaze._

  import scala.concurrent.ExecutionContext.global

  val TestPartCodes = List("6013711", "4211045", "4570027", "4566256")

  def httpClient() =
    BlazeClientBuilder[IO](global).stream.map(org.http4s.client.middleware.Logger(true, false))

  def writeToFile(missingPartsBySeller: List[(String, List[String])]): Unit = {
    import java.io.File
    val outputFile = File.createTempFile(OutputFilePrefix, OutputFileSuffix, new File("."))
    val pw = new java.io.PrintWriter(outputFile)
    val data = missingPartsBySeller.map{ case (seller, missingParts) => s"$seller\t${missingParts.mkString(", ")}" }.mkString("\n")
    pw.write(data)
    pw.close
    println(s"*** data written to file $outputFile ***")
  }

  def readPartCodes(fn: String): List[String] =
    scala.io.Source.fromFile(fn).getLines.toList.map(_.split("\t").head)

  override def run(args: List[String]): IO[ExitCode] =
    (for {
      hc <- httpClient()
      searcher = new LegoSearcher[IO](hc)
      r <- Stream.eval(searcher.rankSellers(readPartCodes(args(0))))
      _ <- Stream.eval(IO(writeToFile(r)))
    } yield r).compile.drain.as(ExitCode.Success)

}


