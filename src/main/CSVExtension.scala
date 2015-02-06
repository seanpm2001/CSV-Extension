package org.nlogo.extensions.csv

import java.io

import org.nlogo.nvm.ExtensionContext

import scala.collection.JavaConverters._

import org.nlogo.api._
import org.nlogo.api.Syntax._

import org.apache.commons.csv._
import java.io._

class CSVExtension extends DefaultClassManager {
  def format(delimiter: Option[String]) =
    (delimiter foldLeft CSVFormat.DEFAULT)(_ withDelimiter _(0))

  def parse(str: String, format: CSVFormat) =
    format.parse(new StringReader(str)).iterator.asScala map (_.iterator.asScala)

  def write(row: Iterator[String], format: CSVFormat) = format.format(row.toSeq:_*)

  def parseValue(entry: String): AnyRef = NumberParser.parse(entry).right getOrElse (entry.toUpperCase match {
    case "TRUE"  => true:  java.lang.Boolean
    case "FALSE" => false: java.lang.Boolean
    case _       => entry
  })

  def liftParser[T](parseItem: T => AnyRef)(row: Iterator[T]): LogoList =
    LogoList.fromIterator(row map parseItem)

  case class ParserPrimitive(process: Iterator[Iterator[String]] => LogoList) extends DefaultReporter {
    override def getSyntax = reporterSyntax(Array(StringType | RepeatableType), ListType, 1)
    override def report(args: Array[Argument], context: Context) =
      process(parse(args(0).getString, format(args.lift(1) map (_.getString))))
  }

  case class FileParserPrimitive(process: Iterator[Iterator[String]] => LogoList) extends DefaultReporter {
    override def getSyntax = reporterSyntax(Array(StringType | RepeatableType), ListType, 1)
    override def report(args: Array[Argument], context: Context) = {
      val filepath = context.asInstanceOf[ExtensionContext].workspace.fileManager.attachPrefix(args(0).getString)
      val parserFormat = format(args.lift(1).map(_.getString))
      try {
        using(new FileReader(new io.File(filepath))) { reader =>
          process(parserFormat.parse(reader).iterator.asScala.map(_.iterator.asScala))
        }
      } catch {
        case e: FileNotFoundException => throw new ExtensionException("Couldn't find file: " + filepath, e)
        case e: IOException => throw new ExtensionException("Couldn't open file: " + filepath, e)
      }
    }
  }

  def rowParser(parseItem: String => AnyRef) = ParserPrimitive { rows =>
    try {
      liftParser(parseItem)(rows.next())
    } catch {
      case (e: NoSuchElementException) => LogoList("")
    }
  }

  def fullParser(parseItem: String => AnyRef) = ParserPrimitive(liftParser(liftParser(parseItem)))

  case class WriterPrimitive(dump: AnyRef => String) extends DefaultReporter {
    override def getSyntax = reporterSyntax(Array(ListType, StringType | RepeatableType), StringType, 1)
    override def report(args: Array[Argument], context: Context) =
      write(args(0).getList.scalaIterator map dump, format(args.lift(1) map (_.getString)))
  }

  override def load(primManager: PrimitiveManager) = {
    val add = primManager.addPrimitive _
    add("from-line", rowParser(parseValue))
    add("from-string", fullParser(parseValue))
    add("from-file", FileParserPrimitive(liftParser(liftParser(parseValue))))
    add("to-line", WriterPrimitive(Dump.logoObject))
  }

  def using[A, B <: {def close(): Unit}] (closeable: B) (f: B => A): A =
    try { f(closeable) } finally { closeable.close() }
}
