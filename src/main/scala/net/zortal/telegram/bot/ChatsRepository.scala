package net.zortal.telegram.bot

import zio._

trait ChatRepository {
  val chatRepository: ChatRepository.Service[Any]
}

object ChatRepository {

  trait Service[R] {
    def getChatIds: ZIO[R, Throwable, Set[Long]]

    def saveChat(chatId: Long): ZIO[R, Throwable, Unit]

    def removeChat(chatId: Long): ZIO[R, Throwable, Unit]
  }

  object > {
    def getChatIds =
      ZIO.accessM[ChatRepository](_.chatRepository.getChatIds)

    def saveChat(chatId: Long) =
      ZIO.accessM[ChatRepository](_.chatRepository.saveChat(chatId))

    def removeChat(chatId: Long) =
      ZIO.accessM[ChatRepository](_.chatRepository.removeChat(chatId))
  }

  class Dummy private (chatIds: Ref[Set[Long]]) extends ChatRepository {

    val chatRepository = new ChatRepository.Service[Any] {
      def getChatIds = chatIds.get

      def saveChat(chatId: Long) =
        chatIds.update(_ + chatId).unit

      def removeChat(chatId: Long) =
        chatIds.update(_ - chatId).unit
    }
  }

  object Dummy {
    def make(): Task[Dummy] =
      Ref.make[Set[Long]](Set.empty).map(new ChatRepository.Dummy(_))
  }
}
