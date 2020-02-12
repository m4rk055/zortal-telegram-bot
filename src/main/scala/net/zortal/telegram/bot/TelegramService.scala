package net.zortal.telegram.bot

import zio._
import io.circe._
import sttp.model.Uri
import sttp.client._
import sttp.client.circe._

// TODO: better model
case class Chat(id: Long)
case class NewMember(username: String)
case class LeftMember(username: String)
case class Message(
  text: Option[String],
  newMember: Option[NewMember],
  leftMember: Option[LeftMember],
  chat: Chat,
  date: Long,
)
case class Update(updateId: Long, message: Option[Message])
case class PollResponse(results: List[Update])

trait TelegramService {
  val telegramService: TelegramService.Service[Any]
}

object TelegramService {

  trait Service[R] {
    def sendMsg(msg: String, chatId: Long, parseMode: String = "Markdown"): ZIO[R, Throwable, Unit]

    def poll(offset: Long): ZIO[R, Throwable, PollResponse]
  }

  def apply(
    endPoint: Uri,
    token: String,
  )(implicit httpClient: SttpBackend[Task, Nothing, NothingT]) = new TelegramService {

    import Decoders._

    val telegramService = new TelegramService.Service[Any] {

      def sendMsg(
        msg: String,
        chatId: Long,
        parseMode: String,
      ): ZIO[Any, Throwable, Unit] = {

        val uri = endPoint
          .path(List(s"bot$token", "sendMessage"))
          .params(
            Map(
              "chat_id"    -> chatId.toString,
              "text"       -> msg,
              "parse_mode" -> parseMode,
            ),
          )

        quickRequest.get(uri).send().ignore
      }

      def poll(offset: Long) = {

        val uri = endPoint
          .path(List(s"bot$token", "getUpdates"))
          .params(
            Map(
              "offset"  -> (offset + 1).toString,
              "timeout" -> "30",
            ),
          )

        quickRequest
          .get(uri)
          .response(asJson[PollResponse])
          .send()
          .flatMap(r => ZIO.fromEither(r.body))
      }
    }
  }
}

object Decoders {

  implicit val chatDecoder: Decoder[Chat] =
    Decoder.forProduct1("id")(Chat.apply)

  implicit val newMemberDecoder: Decoder[NewMember] =
    Decoder.forProduct1("username")(NewMember.apply)

  implicit val leftMemberDecoder: Decoder[LeftMember] =
    Decoder.forProduct1("username")(LeftMember.apply)

  implicit val messageDecoder: Decoder[Message] =
    Decoder.forProduct5("text", "new_chat_member", "left_chat_member", "chat", "date")(
      Message.apply,
    )

  implicit val updateDecoder: Decoder[Update] =
    Decoder.forProduct2("update_id", "message")(Update.apply)

  implicit val pollRespDecoder: Decoder[PollResponse] =
    Decoder.forProduct1("result")(PollResponse.apply)
}
