package com.example

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.{Get, Put}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.OnSuccessMagnet
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.alpakka.csv.scaladsl.{CsvParsing, CsvToMap}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object SyncApp extends AppServer {

  def main(args: Array[String]): Unit = {
    val rootBehavior = Behaviors.setup[Nothing] { context =>
      val server = new SyncServer(ConfigFactory.load())(system = context.system)
      startHttpServer(server.manageRoute, 8080)(context.system)
      Behaviors.empty
    }
    ActorSystem[Nothing](rootBehavior, "syncAkkaSystem")
  }
}



class SyncServer(config: Config)(implicit val system: ActorSystem[_]) {

  val manageRoute: Route =
    pathPrefix("sync") {
      pathEnd {
        concat(
          get {
            onSuccess(OnSuccessMagnet(triggerSync())) { syncResponse =>
              complete(syncResponse.status, Unmarshal(syncResponse).to[String])
            }
          }
        )
      }
    }

  def extractEntityData(response: HttpResponse): Source[ByteString, _] =
    response match {
      case HttpResponse(OK, _, entity, _) => entity.dataBytes
      case notOkResponse =>
        Source.failed(new RuntimeException(s"illegal response $notOkResponse"))
    }

  def cleanseCsvData(csvData: Map[String, ByteString]): Iterable[Article] = {
    val value = csvData
      .filterNot { case (key, _) => key.isEmpty }
      .view
      .mapValues(_.utf8String)
      .toMap
    value.values.map(v => {
      val args = v.split('|')
      //produktId|name|beschreibung|preis|summeBestand
      Article(args(0), args(1), args(2), args(3), args(4).toDouble, args(5).toInt)
    })
  }

  def fetchAndParseArticles: Future[Seq[Article]] = {
    val articlesUrl = config.getString("sync.routes.articles.url")
    val maxFetch = config.getInt("sync.routes.articles.maxFetch")
    val getArticlesUrl = s"$articlesUrl/$maxFetch"

    val articles = Source
      .future(Http().singleRequest(Get(getArticlesUrl)))
      .flatMapConcat(extractEntityData)
      .via(CsvParsing.lineScanner())
      .via(CsvToMap.toMap())
      .map(cleanseCsvData)
      .runWith(Sink.seq)
      .map(_.flatten)
    articles
  }

  def processArticles(articles: Seq[Article]): Future[Iterable[Product]] = Future.successful(
    articles.groupBy(_.produceId).map({
        case (_, items) => {
          val cheapest = items.minBy(_.price)
          val totalStocks = items.map(_.stock).sum
          Product(cheapest.produceId, cheapest.name, cheapest.color, cheapest.price, totalStocks)
        }
      })
  )

  def triggerSync(): Future[HttpResponse] = {
    for {
      articles      <- fetchAndParseArticles
      _             <- Future.successful(system.log.info(s"Articles fetched: ${articles}"))
      products      <- processArticles(articles)
      _             <- Future.successful(system.log.info(s"Products created: ${products}"))
      cvsProducts   <- Future.successful(products.foldLeft(List[String](Product.header))((a, b) => a ++ List(Product.toSVCLine(b))))
      uploadResult  <- {
        val productsUrl = config.getString("sync.routes.products.url")
        val productsCSV = cvsProducts.mkString("\n")
        val httpRequest = Put(s"${productsUrl}/${cvsProducts.size - 1}")
            .withEntity(ContentTypes.`text/csv(UTF-8)`, productsCSV)
        Http().singleRequest(httpRequest)
      }
    } yield {
      system.log.info(s"Products upload result: $uploadResult")
      uploadResult
    }
  }
}

case class Article(id: String, produceId: String, name: String, color: String, price: Double, stock: Int)
case class Product(produceId: String, name: String, color: String, price: Double, stockTotal: Int)
object Product {
  def header: String = "produktId|name|beschreibung|preis|summeBestand"
  def toSVCLine(product: Product): String = s"${product.produceId}|${product.name}|${product.color}|${product.price}|${product.stockTotal}"
}
