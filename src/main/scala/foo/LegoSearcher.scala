package foo

import cats.effect._
import cats.implicits._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.client.Client
import scala.language.higherKinds


object LegoSearcher {
  val BrickLinkBaseUrl = "https://www.bricklink.com"

  case class ItemResponse(result: ItemResult)
  case class ItemResult(typeList: List[ItemTypeObj])
  case class ItemTypeObj(`type`: String, items: List[Item])
  case class Item(idItem: Int, strItemNo: String, strItemName: String, strPCC: String)

  case class InvResponse(list: List[InvItem])
  case class InvItem(strStorename: String)
}

class LegoSearcher[F[_]: ConcurrentEffect](val hc: Client[F]) extends Http4sClientDsl[F] {
  import org.http4s.circe.CirceEntityDecoder._
  import io.circe.generic.auto._
  import cats.data.EitherT
  import LegoSearcher._

  def getItem(partCode: String) = {
    val url = s"$BrickLinkBaseUrl/ajax/clone/search/searchproduct.ajax?q=${partCode}&st=0&brand=1000&yf=0&yt=0&reg=0&ca=0&nmp=0&color=-1&min=0&max=0&minqty=0&nosuperlot=1&incomplete=0&showempty=1&rpp=25&pi=1&ci=0"
    hc.expect[ItemResponse](url) map { r =>
      r.result.typeList.headOption.flatMap(_.items.headOption)
    }
  }

  def getItemSellers(itemId: Int) = {
    val Page = 500
    val url = s"$BrickLinkBaseUrl/ajax/clone/catalogifs.ajax?itemid=${itemId}&iconly=0&rpp=${Page}"
    hc.expect[InvResponse](url) map { r =>
      r.list.map(_.strStorename).toSet.toList
    }
  }

  def getPartSellers(partCode: String) =
    (for {
      r1 <- EitherT.fromOptionF(getItem(partCode), s"no item found for partcode $partCode")
      r2 <- EitherT.right[String](getItemSellers(r1.idItem))
    } yield r2).value

  def rankSellers(partCodes: List[String]) = {
    partCodes.map(c => getPartSellers(c)).traverse(a => a).map { sellers =>
      sellers.flatMap(_.getOrElse(List.empty)).groupBy(a => a).
        map{case (v, l) => v -> l.size}.toSeq.sortBy(-_._2).take(20)
    }
  }
}

object Searcher extends IOApp {
  import cats.effect._
  import scala.concurrent.ExecutionContext.global
  import org.http4s.client.blaze._
  import fs2.Stream

  val PartCodes = List("6013711", "4211045", "4570027", "4566256")

  override def run(args: List[String]): IO[ExitCode] =
    (for {
      hc <- BlazeClientBuilder[IO](global).stream
      searcher = new LegoSearcher[IO](hc)
      r <- Stream.eval(searcher.rankSellers(PartCodes))
    } yield {
      println(r.mkString("\n"))
      r
    }).compile.drain.as(ExitCode.Success)

}


