import java.time.Instant
import java.util.concurrent.TimeUnit

import Storage.Message
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import spray.json.RootJsonFormat

import scala.collection.mutable

trait JsonFormatter extends SprayJsonSupport {

  import spray.json.DefaultJsonProtocol._

  implicit val todoFormat: RootJsonFormat[Todo] = jsonFormat3(Todo)
  implicit val todosFormat: RootJsonFormat[Todos] = jsonFormat1(Todos)
  implicit val responseFormat: RootJsonFormat[Response] = jsonFormat1(Response)
  implicit val messageFormat: RootJsonFormat[Message] = jsonFormat1(Message)
}

trait Routes extends JsonFormatter {

  import Storage._
  import akka.http.scaladsl.server.Directives._
  import akka.pattern.ask

  implicit val timeout: Timeout = Timeout(5, TimeUnit.SECONDS)
  implicit val storage: ActorRef

  lazy val routes: Route = {
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
          },
          put {
            entity(as[Todo]) { todo => {
              if (todo.content.isEmpty) {
                val result = (storage ? DeleteTodo(todo)).mapTo[Todo]
                complete(result)
              } else {
                val result = (storage ? UpdateTodo(todo)).mapTo[Todo]
                complete(result)
              }
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

class Storage extends Actor with ActorLogging {

  import Storage._

  override def receive: Receive = {
    case GetTodos =>
      sender() ! Todos(todosList.values.toSeq)
    case CreateTodo(todo: Todo) =>
      todosList.put(todo.date, todo)
      sender() ! todo
    case DeleteTodo(todo) =>
      todosList.remove(todo.date)
      sender() ! todo
    case UpdateTodo(todo) =>
      todosList.put(todo.date, todo)
      sender() ! todo
  }
}

object Storage {

  final case class GetTodo(id: String)

  final case class GetTodos()

  final case class UpdateTodo(todo: Todo)

  final case class CreateTodo(todo: Todo)

  final case class DeleteTodo(todo: Todo)

  final case class Message(message: String)

  var todosList = mutable.HashMap.empty[String, Todo]

}

object Server extends App with Routes {
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val storage = system.actorOf(Props[Storage])

  Http().bindAndHandle(routes, "localhost", 8080)
  println("http://localhost:8080")
}