package net.zortal.telegram.bot

import zio._
import zio.internal.Executor
import scala.util.Try
import com.google.cloud.firestore._
import com.google.api.core._
import java.util.HashMap

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

class FirestoreChatRepository private (
  localChatIds: Ref[Set[Long]],
  executor: Executor,
  db: Firestore,
  chatsCollection: String,
) extends ChatRepository {

  import scala.jdk.CollectionConverters._

  // TODO: better error model (NoSuchElementException)

  private def getDocumentSnapshot(id: Long) =
    ZIO.effectAsync { (callback: Task[DocumentSnapshot] => Unit) =>
      db.collection(chatsCollection)
        .whereEqualTo("id", id)
        .addSnapshotListener(
          executor.asECES,
          new EventListener[QuerySnapshot] {
            def onEvent(q: QuerySnapshot, e: FirestoreException) =
              if (q != null)
                callback(ZIO.fromTry(Try(q.iterator().next())))
              else
                callback(ZIO.fail(e))
          },
        )
    }

  private def delete(id: Long) =
    getDocumentSnapshot(id).foldM(
      e =>
        e match {
          case _: NoSuchElementException => ZIO.unit
          case _                         => ZIO.fail(e)
        },
      doc =>
        ZIO.effectAsync { (callback: Task[Unit] => Unit) =>
          val deleteFuture = doc.getReference.delete

          val cb = new ApiFutureCallback[WriteResult] {
            override def onFailure(t: Throwable): Unit = callback(ZIO.fail(t))

            override def onSuccess(result: WriteResult): Unit = callback(ZIO.unit)
          }

          ApiFutures.addCallback(deleteFuture, cb, executor.asECES)
        },
    )

  private def insert(id: Long) =
    ZIO.effectAsync { (callback: Task[Unit] => Unit) =>
      val data = new HashMap[String, Any]()
      data.put("id", id)

      val addFuture = db.collection(chatsCollection).add(data)

      val cb = new ApiFutureCallback[DocumentReference] {
        override def onFailure(t: Throwable): Unit = callback(ZIO.fail(t))

        override def onSuccess(result: DocumentReference): Unit = callback(ZIO.unit)
      }

      ApiFutures.addCallback(addFuture, cb, executor.asECES)
    }

  private def exists(id: Long) =
    getDocumentSnapshot(id).foldM(
      e =>
        e match {
          case _: NoSuchElementException => ZIO.effectTotal(false)
          case _                         => ZIO.fail(e)
        },
      _ => ZIO.effectTotal(true),
    )

  private def getAllChatIds =
    ZIO.effectAsync { (callback: Task[List[Long]] => Unit) =>
      db.collection(chatsCollection)
        .addSnapshotListener(new EventListener[QuerySnapshot] {
          def onEvent(q: QuerySnapshot, e: FirestoreException) =
            if (q != null)
              callback(
                ZIO.effect(List.from(q.getDocuments().asScala.map(_.getLong("id")))),
              )
            else
              callback(ZIO.fail(e))
        })
    }

  private def updateLocalChatIds =
    getAllChatIds.flatMap(chatIds => localChatIds.set(chatIds.toSet))

  val chatRepository = new ChatRepository.Service[Any] {
    def getChatIds =
      localChatIds.get

    def saveChat(chatId: Long) =
      exists(chatId).flatMap(exist =>
        if (exist) ZIO.unit
        else insert(chatId) *> updateLocalChatIds,
      )

    def removeChat(chatId: Long) =
      delete(chatId) *> updateLocalChatIds
  }
}

object FirestoreChatRepository {

  def make(executor: Executor, projectId: String, chatsCollection: String) =
    for {
      chatIds <- Ref.make[Set[Long]](Set.empty)

      firestoreOptions <- ZIO.effect(
                           FirestoreOptions
                             .getDefaultInstance()
                             .toBuilder()
                             .setProjectId(projectId)
                             .build(),
                         )
      db <- ZIO.effect(firestoreOptions.getService())

      firestore = new FirestoreChatRepository(chatIds, executor, db, chatsCollection)

      _ <- firestore.updateLocalChatIds

    } yield firestore
}
