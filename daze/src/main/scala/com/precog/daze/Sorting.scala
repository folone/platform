/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.daze

import com.precog.yggdrasil._
import com.precog.common.VectorCase
import com.precog.util._

import scala.annotation.tailrec
import scalaz._

trait Sorting[Dataset[_]] {
  def sort[E <: AnyRef](values: Dataset[E], filePrefix: String, memoId: Int, memoCtx: MemoizationContext)(implicit order: Order[E], cm: ClassManifest[E]): Dataset[E]
}

class IteratorSorting(sortConfig: SortConfig) extends Sorting[Iterator] {
  def sort[E <: AnyRef](values: Iterator[E], filePrefix: String, memoId: Int, memoCtx: MemoizationContext)(implicit order: Order[E], cm: ClassManifest[E], fs: SortSerialization[E]): Iterator[E] = {
    import java.io.File
    import java.util.{PriorityQueue, Comparator, Arrays}

    val javaOrder = order.toJavaComparator

    def sortFile(i: Int) = new File(sortConfig.sortWorkDir, filePrefix + "." + i)

    val buffer = new Array[E](sortConfig.sortBufferSize)

    def writeSortedChunk(chunkId: Int, v: Iterator[E]): File = {
      @tailrec def insert(j: Int, iter: Iterator[E]): Int = {
        if (j < buffer.length && iter.hasNext) {
          buffer(j) = iter.next()
          insert(j + 1, iter)
        } else j
      }

      insert(0, values)
      Arrays.sort(buffer, javaOrder)
      val chunkFile = sortFile(chunkId)
      fs.write(fs.oStream(chunkFile), buffer)
      chunkFile
    }

    @tailrec def writeChunked(files: Vector[File]): Vector[File] = {
      if (values.hasNext) writeChunked(files :+ writeSortedChunk(files.length, values))
      else files
    }

    def mergeIterator(streams: Vector[Iterator[E]]): Iterator[E] = {
      class Cell(iter: Iterator[E]) {
        var value: E = iter.next
        def advance(): Unit = {
          value = if (iter.hasNext) iter.next else null.asInstanceOf[E]
          this
        }
      }

      val cellComparator = order.contramap((c: Cell) => c.value).toJavaComparator

      new Iterator[E] {
        private val heads: PriorityQueue[Cell] = new PriorityQueue[Cell](streams.size, cellComparator) 
        streams.foreach(i => heads.add(new Cell(i)))

        def hasNext = !heads.isEmpty
        def next = {
          assert(!heads.isEmpty) 
          val cell = heads.poll
          val result = cell.value
          cell.advance
          if (cell.value != null) heads.offer(cell)
          result
        }
      }
    }

    val chunkFiles = writeChunked(Vector.empty[File])
    mergeIterator(chunkFiles.map(f => fs.reader(fs.iStream(f))).filter(_.hasNext))
  }
}

// vim: set ts=4 sw=4 et:
