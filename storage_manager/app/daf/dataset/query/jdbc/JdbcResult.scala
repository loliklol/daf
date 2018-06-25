package daf.dataset.query.jdbc

import java.lang.{ Boolean => JavaBoolean, Double => JavaDouble, Float => JavaFloat, Integer => JavaInteger, Long => JavaLong }
import java.sql.{ Timestamp, Array => JdbcArray, Struct => JdbcStruct }
import java.time.{ LocalDateTime, ZoneOffset }
import java.time.format.DateTimeFormatter

import cats.free.Free
import cats.instances.list.catsStdInstancesForList
import cats.instances.try_.catsStdInstancesForTry
import cats.syntax.traverse.toTraverseOps
import daf.dataset.cleanCsv
import play.api.libs.json._

import scala.util.Try

case class JdbcResult(header: Header, rows: Stream[Row]) {

  private val index = header.zipWithIndex.map { case (col, i) => i -> col}.toMap[Int, String]

  private def anyJson(value: Any): Trampoline[JsValue] = value match {
    case number: JavaInteger       => Free.pure[Try, JsValue]  { JsNumber(BigDecimal(number)) }
    case number: JavaLong          => Free.pure[Try, JsValue]  { JsNumber(BigDecimal(number)) }
    case number: JavaDouble        => Free.pure[Try, JsValue]  { JsNumber(BigDecimal(number)) }
    case number: JavaFloat         => Free.pure[Try, JsValue]  { JsNumber(BigDecimal(number)) }
    case boolean: JavaBoolean      => Free.pure[Try, JsValue]  { JsBoolean(Boolean.unbox(boolean)) }
    case s: String                 => Free.pure[Try, JsValue]  { JsString(s) }
    case _: JdbcArray | _: JdbcStruct => Free.defer[Try, JsValue] { complexJson(value) }
    case seq: Seq[_]               => Free.defer[Try, JsValue] { complexJson(seq) }
    case timestamp: Timestamp      => Free.pure[Try, JsValue]  {
      JsString { timestamp.toLocalDateTime.atOffset(ZoneOffset.UTC).format { DateTimeFormatter.ISO_OFFSET_DATE_TIME } }
    }
    case _                         => recursionError[JsValue] { new RuntimeException(s"Unable to convert jdbc value of type [${value.getClass.getName}] to JSON") }
  }

  private def complexJson(value: Any): Trampoline[JsValue] = value match {
    case seq: Seq[_]          => seq.toList.traverse[Trampoline, JsValue] { anyJson }.map { JsArray }
    case array: JdbcArray     => Free.pure[Try, Row] { array.getArray.asInstanceOf[Array[AnyRef]].toList }.flatMap { anyJson }
    case struct: JdbcStruct   => Free.defer[Try, JsValue] { anyJson(struct.getAttributes.toList) }
    case _                    => recursionError[JsValue] { new RuntimeException(s"Unable to convert jdbc value of type [${value.getClass.getName}] to JSON") }
  }

  private def json(row: Row) = row.traverse[Trampoline, JsValue] { anyJson }.map { values =>
    JsObject {
      values.zipWithIndex.map { case (v, i) => index(i) -> v }
    }
  }

  def toCsv: Stream[String] =
    header.map { h => s""""$h"""" }.mkString(", ") +:
    rows.map { _.map { cleanCsv }.mkString(", ") }

  def toJson: Stream[JsObject] = rows.map { json(_).runTailRec.get } // let throw in case of error
}
