package com.websudos.phantom.tables

import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executor

import com.websudos.phantom.builder.query.InsertQuery
import com.websudos.phantom.dsl._
import com.websudos.phantom.util.ByteString

import scala.concurrent.{ExecutionContext, Future}

case class BufferRecord(id: UUID, buffer: ByteBuffer, stringBuffer: ByteString)

sealed class ByteBufferTable extends CassandraTable[ConcreteByteBufferTable, BufferRecord] {
  object id extends UUIDColumn(this) with PartitionKey[UUID]

  object buffer extends BlobColumn(this)

  object stringBuffer extends ByteStringColumn(this)

  def fromRow(row: Row): BufferRecord = {
    BufferRecord(
      id(row),
      buffer(row),
      stringBuffer(row)
    )
  }
}

abstract class ConcreteByteBufferTable extends ByteBufferTable with RootConnector {

  def store(record: BufferRecord): InsertQuery.Default[ConcreteByteBufferTable, BufferRecord] = {
    insert.value(_.id, record.id)
      .value(_.buffer, record.buffer)
      .value(_.stringBuffer, record.stringBuffer)
  }

  def getById(id: UUID)(implicit executor: Executor, context: ExecutionContext): Future[Option[BufferRecord]] = {
    select.where(_.id eqs id).one()
  }






}
