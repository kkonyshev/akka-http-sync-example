package com.example

import java.io.File

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpEntity.Chunked
import akka.http.scaladsl.model.{ContentTypes, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
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
                  complete((StatusCodes.OK, performed))
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
    Future.successful(writeToTempFile(cvsData.utf8String).getAbsolutePath)
  }

  import java.io.{File, PrintWriter}
  def writeToTempFile(contents: String,
                      prefix: Option[String] = None,
                      suffix: Option[String] = None): File = {
    val tempFi = File.createTempFile(prefix.getOrElse("prefix-"),
      suffix.getOrElse("-suffix"))
    tempFi.deleteOnExit()
    new PrintWriter(tempFi) {
      // Any statements inside the body of a class in scala are executed on construction.
      // Therefore, the following try-finally block is executed immediately as we're creating
      // a standard PrinterWriter (with its implementation) and then using it.
      // Alternatively, we could have created the PrintWriter, assigned it a name,
      // then called .write() and .close() on it. Here, we're simply opting for a terser representation.
      try {
        write(contents)
      } finally {
        close()
      }
    }
    tempFi
  }
}
