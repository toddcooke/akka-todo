import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import spray.json.RootJsonFormat

trait JsonFormatter extends SprayJsonSupport {

  import spray.json.DefaultJsonProtocol._

  implicit val todosFormat: RootJsonFormat[Todos] = jsonFormat1(Todos)
  implicit val todoFormat: RootJsonFormat[Todo] = jsonFormat3(Todo)
  implicit val responseFormat: RootJsonFormat[Response] = jsonFormat1(Response)
}

trait Routes extends JsonFormatter {

  import Storage._
  import akka.http.scaladsl.server.Directives._
  import akka.pattern.ask

  implicit val timeout: Timeout = Timeout(5, TimeUnit.SECONDS)
  implicit val storage: ActorRef

  val routes: Route = {
    concat(
      pathEndOrSingleSlash {
        getFromFile("src/main/scala/index.html")
      },
      pathPrefix("todos") {
        concat(
          get {
            val todos = (storage ? GetTodos).mapTo[Todos]
            complete(todos)
          },
          post {
            entity(as[String]) { content => {
              val todo = Todo(content, Instant.now.getEpochSecond.toString, done = false)
              val result = (storage ? CreateTodo(todo)).mapTo[Todo]
              complete(result)
            }
            }
          }
        )
      }
    )
  }

}

final case class Todo(content: String, date: String, done: Boolean)

final case class Todos(todos: Seq[Todo])

final case class Response(message: String)

object Storage {

  val todosList = Seq.empty[Todo]

  final case class GetTodo(id: String)

  final case class GetTodos()

  final case class CreateTodo(todo: Todo)

}

class Storage extends Actor {

  import Storage._

  override def receive: Receive = {
    case GetTodos =>
      sender() ! Todos(todosList)
    case CreateTodo(todo: Todo) =>
      todosList :+ todo
      sender() ! todo
  }
}

object Server extends App with Routes {
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val storage = system.actorOf(Props[Storage])

  Http().bindAndHandle(routes, "localhost", 8080)
  println("http://localhost:8080")
}