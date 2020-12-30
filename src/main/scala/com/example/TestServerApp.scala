package com.example

import java.io.File

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpEntity.Chunked
import akka.http.scaladsl.model.{ContentTypes, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.alpakka.csv.scaladsl.{CsvParsing, CsvToMap}
import akka.stream.scaladsl.Sink
import akka.util.ByteString

import scala.concurrent.Future

object TestServerApp extends AppServer {
  def main(args: Array[String]): Unit = {
    val rootBehavior = Behaviors.setup[Nothing] { context =>
      val server = new TestServer()(context.system)
      startHttpServer(server.articlesRoute ~ server.productsRoute, 8081)(context.system)
      Behaviors.empty
    }
    ActorSystem[Nothing](rootBehavior, "testAkkaSystem")
  }
}

class TestServer()(implicit val system: ActorSystem[_]) {
  val articlesRoute: Route =
    pathPrefix("articles") {
      concat(
        path(Segment) { linesNumber =>
          concat(
            get {
              onSuccess(fileAsSource(linesNumber.toInt)) { chunks =>
                complete(HttpResponse(entity = Chunked.fromData(ContentTypes.`text/csv(UTF-8)`, chunks)))
              }
            }
          )
        })
    }

  val productsRoute: Route =
    pathPrefix("products") {
      concat(
        path(Segment) { linesNumber =>
          concat(
            put {
              entity(as[ByteString]) { data =>
                onSuccess(parseAsCSV(data)) { performed =>
                  complete((StatusCodes.OK))
                }
              }
            }
          )
        })
    }

  private def fileAsSource(maxLines: Int) = {
    val source = scala.io.Source.fromFile (new File (s"./src/main/resources/input.csv") )
    val linesIterator = source.getLines.take(maxLines + 1)
    val outputSource = akka.stream.scaladsl.Source.fromIterator (() => linesIterator).map(s => ByteString (s"$s\n") )
    Future.successful(outputSource)
  }

  private def parseAsCSV(cvsData: ByteString) = {
    val cvsFile = akka.stream.scaladsl.Source.single(cvsData)
      .via(CsvParsing.lineScanner(delimiter = '|'))
      .via(CsvToMap.toMap())
      .runWith(Sink.seq)
    cvsFile
  }
}
