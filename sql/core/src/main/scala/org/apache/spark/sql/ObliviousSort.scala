package oblivious_sort

import java.lang.ThreadLocal
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Map
import scala.collection.mutable.PriorityQueue
import scala.collection.mutable.SynchronizedSet
import scala.math.BigInt
import scala.reflect.ClassTag
import scala.util.Random

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.QED
import org.apache.spark.sql.QEDOpcode
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.Block
import org.apache.spark.storage.StorageLevel

import org.apache.spark.sql.MutableInteger
import org.apache.spark.sql.QEDOpcode

object ObliviousSort extends java.io.Serializable {

  val Multiplier = 2 // TODO: fix bug when this is 1

  import QED.{time, logPerf}

  class Value(r: Int, c: Int, v: Array[Byte]) extends java.io.Serializable {
    def this(r: Int, c: Int) = this(r, c, new Array[Byte](0))
    var row: Int = r
    var column: Int = c
    var value: Array[Byte] = v
  }

  def log2(i: Int): Int = {
    math.ceil((math.log(i) / math.log(2)).toInt).toInt
  }

  // this function performs an oblivious sort on an array of (column, (row, value))
  def OSortSingleMachine_WithIndex(
      values: Array[Value], low_idx: Int, len: Int, opcode: QEDOpcode) = {

    val valuesSlice = values.slice(low_idx, low_idx + len)

    // Concatenate encrypted rows into a buffer
    val nonEmptyRows = valuesSlice.map(_.value).filter(_.length != 0)
    val concatRows = QED.createBlock(nonEmptyRows, opcode.isJoin)

    // Sort rows in enclave
    val (enclave, eid) = QED.initEnclave()
    val allRowsSorted =
      if (nonEmptyRows.nonEmpty) {
        enclave.ObliviousSort(eid, opcode.value, concatRows, 0, nonEmptyRows.length)
      }
      else Array.empty[Byte]
    // enclave.StopEnclave(eid)

    // Copy rows back into values
    val sortedRowIter =
      if (nonEmptyRows.nonEmpty) {
        QED.splitBlock(allRowsSorted, nonEmptyRows.length, opcode.isJoin)
      } else {
        Iterator.empty
      }
    for (row <- valuesSlice) {
      if (sortedRowIter.hasNext) {
        row.value = sortedRowIter.next()
      } else {
        row.value = new Array[Byte](0)
      }
    }
  }

  def Transpose(value: Value, r: Int, s: Int): Unit = {
    val row = value.row
    val column = value.column

    val idx = (column - 1) * r + row
    val new_row = (idx - 1) / s + 1
    val new_column = (idx + s - 1) % s + 1
    //println("Transpose: (" + row + ", " + column + ")" + " --> (" + new_row + ", " + new_column + ")")

    value.row = new_row
    value.column = new_column
  }

  def ColumnSortParFunction1(index: Int, it: Iterator[(Array[Byte], Int)],
      numPartitions: Int, r: Int, s: Int, opcode: QEDOpcode): Iterator[Value] = {

    val rounds = s / numPartitions
    var ret_result = Array.empty[Value]
    time("Column sort 1-- array allocation time") {
      ret_result = Array.fill[Value](r * rounds)(new Value(0, 0))
    }

    // println("Ret_result's array size is " + (r * rounds))
    // println("Total number of rounds: " + rounds)

    var counter = 0
    for (v <- it) {
      ret_result(counter).value = v._1
      counter += 1
    }

    val array_len = counter / (s / numPartitions)

    time("Column sort, step 1, total sort time") {
      for (rnd <- 0 to rounds - 1) {

        time("Column sort, step 1") {
          OSortSingleMachine_WithIndex(ret_result, rnd * array_len, array_len, opcode)
        }

        // add result to ret_result
        val column = index * rounds + rnd  + 1
        //println("Index is " + index + ", Column: " + column)

        for (idx <- 0 to r - 1) {

          val index = rnd * array_len + idx

          ret_result(index).column = column
          ret_result(index).row = idx + 1
          Transpose(ret_result(index), r, s)

        }
      }
    }

    ret_result.iterator
  }

  def ColumnSortStep3(
      key: (Int, Iterable[(Int, Array[Byte])]), r: Int, s: Int, opcode: QEDOpcode,
      NumMachines: Int, NumCores: Int)
    : Iterator[Value] = {

    var len = 0
    var i = 0

    for (iter <- 0 to r - 1) {
      // output = (value, row, column)
      val old_column = key._1
      val old_row = i + 1

      val idx = (old_row - 1) * s + old_column
      val new_row = (idx + r - 1) % r + 1
      val new_column = (idx - 1) / r + 1

      if ((new_column % (NumCores * Multiplier) == 0 && new_column != s) 
        || (new_column % (NumCores * Multiplier) == 1 && new_column != 1)) {
        len += 1
      }

      len += 1

      i += 1
    }

    var ret_result = Array.fill[Value](len)(new Value(0, 0))
    var counter = 0

    for (v <- key._2) {
      ret_result(counter).value = v._2
      //println("ret_result's value for col " + ret_result(counter).column + ": " + ret_result(counter).value)
      counter += 1
    }

    // println(s"Filled counter $counter vs. len $len")

    time("Column sort, step 3") {
      OSortSingleMachine_WithIndex(ret_result, 0, r, opcode)
    }


    // append result with row and column
    i = 0
    var additional_index = r
    for (idx <- 0 to r - 1) {
      // output = (value, row, column)
      val old_column = key._1
      val old_row = i + 1

      val index = (old_row - 1) * s + old_column
      val new_row = (index + r - 1) % r + 1
      val new_column = (index - 1) / r + 1

      val final_column = (new_column - 1) / (NumCores * Multiplier) + 1
      val final_row = new_column

      //println("[(col, row)] (" + new_column + ", " + new_row + ") --> (" + final_column + ", " + final_row + ")")

      if (new_column % (Multiplier * NumCores) == 0 && new_column != s && final_column < NumMachines) {
        ret_result(additional_index).column = final_column + 1
        ret_result(additional_index).row = final_row
        ret_result(additional_index).value = ret_result(idx).value
        //println("[(col, row)] (" + new_column + ", " + new_row + ") --> (" + (final_column + 1) + ", " + final_row + ")")
        additional_index += 1
      } else if (new_column % (Multiplier * NumCores) == 1 && new_column != 1 && final_column > 1) {
        ret_result(additional_index).column = final_column - 1
        ret_result(additional_index).row = final_row
        ret_result(additional_index).value = ret_result(idx).value
        //println("[(col, row)] (" + new_column + ", " + new_row + ") --> (" + (final_column - 1) + ", " + final_row + ")")
        additional_index += 1
      }

      ret_result(idx).column = final_column
      ret_result(idx).row = final_row

      i += 1
    }

    ret_result.iterator
  }

  def ColumnSortFinal(
      key: (Int, Iterable[(Int, Array[Byte])]), r: Int, s: Int, opcode: QEDOpcode,
      NumMachines: Int, NumCores: Int): Iterator[(Int, (Int, Array[Byte]))] = {

    var result = Array.empty[Value]
    var num_columns = 0
    var min_col = 0

    // println("ColumnSortFinal: column is " + key._1 + ", length is " + key._2.toArray.length)

    if (key._1 == 1 || key._1 == NumMachines) {
      result = Array.fill[Value](r * Multiplier * NumCores + r)(new Value(0, 0))
      num_columns = Multiplier * NumCores + 1
      if (key._1 == 1) {
        min_col = 1
      } else {
        min_col = Multiplier * NumCores * (NumMachines - 1)
      }
    } else {
      result = Array.fill[Value](r * Multiplier * NumCores + 2 * r)(new Value(0, 0))
      num_columns = Multiplier * NumCores + 2
      min_col = Multiplier * NumCores * (key._1 - 1) 
    }


    var counter = Map[Int, Int]()

    time("Single threaded allocation") {
      for (v <- key._2) {
        val col = v._1
        if (!counter.contains(col)) {
          counter(col) = 0
        }
        
        val index = counter(col)
        //if (key._1 == 1) {
        val offset = (col - min_col) * r
        result(index + offset).column = v._1
        result(index + offset).column = index + 1
        result(index + offset).value = v._2
        //}

        counter(col) += 1
      }
    }

    // run column sort in parallel

    time("First sort") {
      val threads = for (i <- 1 to NumCores) yield new Thread() {
        override def run() {
          var offset = r
          if (key._1 == 1) {
            offset = 0
          }

          if (i == NumCores && key._1 < NumMachines) {
            // also sort the last piece
            OSortSingleMachine_WithIndex(result, NumCores * (Multiplier * r), r, opcode)
            // println("[" + key._1 + " - 1] Sorting from " + NumCores * (Multiplier * r) + " for len " + r)
          } else if (i == 1 && key._1 > 1) {
            // also want to sort the first piece
            OSortSingleMachine_WithIndex(result, 0, r, opcode)
            // println("[" + key._1 + " - 2] Sorting from 0 for len" + r)
          }

          OSortSingleMachine_WithIndex(result, (i - 1) * (Multiplier * r) + offset, Multiplier * r, opcode)
          // println("[" + key._1 + " - 3] Sorting " + (i - 1) * (Multiplier * r) + offset + " for len " +  Multiplier * r)
        }
      }

      for (t <- threads) {
        t.start()
      }

      for (t <- threads) {
        t.join()
      }
    }

    time("Second sort") {
      val threads_2 = for (i <- 1 to NumCores + 1) yield new Thread() {
        override def run() {
          var offset = 0
          if (key._1 == 1) {
            offset = -1 * r
          }
          if (!(key._1 == 1 && i == 1) && !(key._1 == NumMachines && i == NumCores + 1)) {
            val low_index = (i - 1) * (Multiplier * r) + offset
            // println("Sorting array from " + low_index + " for length " + 2 * r + ", for column " + key._1 + ", total length: " + result.length)
            OSortSingleMachine_WithIndex(result, low_index, 2 * r, opcode)
          }
        }
      }

      for (t <- threads_2) {
        t.start()
      }

      for (t <- threads_2) {
        t.join()
      }
    }

    var final_result = ArrayBuffer.empty[(Int, (Int, Array[Byte]))]
    var final_offset = r
    if (key._1 == 1) {
      final_offset = 0
    }

    var begin_index = r
    if (key._1 == 1) {
      begin_index = 0
    }

    val end_index = r * Multiplier * NumCores + begin_index - 1
    //println("Result's length is " + result.length + ", end_index is " + end_index)

    var final_counter = 0
    for (idx <- begin_index to end_index) {
      val new_col = (final_counter) / r + (key._1 - 1) * (NumCores * Multiplier) + 1
      val new_row = (final_counter) % r + 1

      final_result += ((new_col, (new_row, result(idx).value)))
      final_counter += 1
      //println("[" + new_col + ", " + new_row + "]: " + result(idx).value + ", old column is " + key._1)
    }

    // println("Final result's length is " + final_result.length)

    final_result.iterator

  }

  def sortBlocks(data: RDD[Block], opcode: QEDOpcode): RDD[Block] = {
    if (data.partitions.length <= 1) {
      data.map { block =>
        val (enclave, eid) = QED.initEnclave()
        val sortedRows = enclave.ObliviousSort(eid, opcode.value, block.bytes, 0, block.numRows)
        Block(sortedRows, block.numRows)
      }
    } else {
      // val rows = data.flatMap(block => QED.splitBlock(block.bytes, block.numRows, opcode.isJoin))
      // val result = ColumnSort(data.context, rows, opcode).mapPartitions { rowIter =>
      //   val rowArray = rowIter.toArray
      //   Iterator(Block(QED.createBlock(rowArray, opcode.isJoin), rowArray.length))
      // }
      // assert(result.partitions.length == data.partitions.length)

      val result = NewColumnSort(data.context, data, opcode)
      result
    }
  }

  // this sorting algorithm is taken from "Tight Bounds on the Complexity of Parallel Sorting"
  def ColumnSort(
      sc: SparkContext, data: RDD[Array[Byte]], opcode: QEDOpcode, r_input: Int = 0, s_input: Int = 0)
      : RDD[Array[Byte]] = {

    val NumMachines = data.partitions.length
    val NumCores = 1

    // let len be N
    // divide N into r * s, where s is the number of machines, and r is the size of the
    // constraints: s | r; r >= 2 * (s-1)^2

    data.cache()

    val len = data.count

    var s = s_input
    var r = r_input

    if (r_input == 0 && s_input == 0) {
      s = Multiplier * NumMachines * NumCores
      r = (math.ceil(len * 1.0 / s)).toInt
    }

    // println("s is " + s + ", r is " + r)

    if (r < 2 * math.pow(s, 2).toInt) {
      logPerf(s"Padding r from $r to ${2 * math.pow(s, 2).toInt}. s=$s, len=$len")
      r = 2 * math.pow(s, 2).toInt
    }

    val padded =
      if (len != r * s) {
        logPerf(s"Padding len from $len to ${r*s}. r=$r, s=$s")
        assert(r * s > len)
        val firstPartitionSize =
          data.mapPartitionsWithIndex((index, iter) =>
            if (index == 0) Iterator(iter.size) else Iterator(0)).collect.sum
        val paddingSize = r * s - len.toInt
        data.mapPartitionsWithIndex((index, iter) =>
          if (index == 0) iter.padTo(firstPartitionSize + paddingSize, new Array[Byte](0))
          else iter)
      } else {
        data
      }

    val numPartitions = NumCores * NumMachines
    val par_data =
      time("prepartitioning") {
        val result = padded.zipWithIndex.map(t => (t._1, t._2.toInt))
          .groupBy((x: (Array[Byte], Int)) => x._2 / r + 1, numPartitions).flatMap(_._2)
          .cache()
        result.count
        result
      }

    val par_data_1_2 = par_data.mapPartitionsWithIndex((index, x) =>
      ColumnSortParFunction1(index, x, NumCores * NumMachines, r, s, opcode))

    val par_data_intermediate = par_data_1_2.map(x => (x.column, (x.row, x.value)))
      .groupByKey(numPartitions).flatMap(x => ColumnSortStep3(x, r, s, opcode, NumMachines, NumCores))
    val par_data_i2 = par_data_intermediate.map(x => (x.column, (x.row, x.value)))
      .groupByKey(numPartitions).flatMap(x => ColumnSortFinal(x, r, s, opcode, NumMachines, NumCores))
    val par_data_final =
      time("final partition sorting") {
        val result = par_data_i2
          .map(v => ((v._1, v._2._1), v._2._2))
          .sortByKey()
          .cache()
        result.count
        result
      }

    par_data_final.map(_._2).filter(_.nonEmpty)
  }

  def GenRandomData(offset: Int, len: Int): Seq[(Int, Int)] ={
    val r = Random
    var inp = Array.fill[(Int, Int)](len)(0, 0)

    for (i <- 0 to len - 1) {
      inp(i) = (r.nextInt(), offset * len + i)
    }

    inp
  }

  def CountRows(key: Int, data: Iterator[Block]): Iterator[(Int, Int)] = {
    var numRows = 0
    for (v <- data) {
      numRows += v.numRows
    }
    Iterator((key, numRows))
  }

  def EnclaveCountRows(data: Array[Byte]): Int = {
    val (enclave, eid) = QED.initEnclave()
    enclave.CountNumRows(eid, data)
  }

  def ParseData(input: (Int, Array[Byte]), r: Int, s: Int): Iterator[(Int, (Int, Array[Byte]))] = {
    // split a buffer into s separate buffer, one for each column
    val prev_column = input._1
    val data = input._2

    val columns = new Array[(Int, (Int, Array[Byte]))](s)

    val buf = ByteBuffer.wrap(data)
    buf.order(ByteOrder.LITTLE_ENDIAN)
    for (c <- 1 to s) {
      // read the column ID
      val columnID = buf.getInt()
      val columnSize = buf.getInt()

      //println(s"ParseData: $columnID, $columnSize")

      val column = new Array[Byte](columnSize)
      buf.get(column)
      columns(c-1) = (c, (prev_column, column))
    }

    columns.iterator
  }

  def ParseDataPostProcess(key: (Int, Iterable[(Int, Array[Byte])]), round: Int, r: Int, s: Int): Iterator[(Int, Array[Byte])] = {

    var joinArray = new Array[Byte](0)
    val column = key._1

    var arrays = key._2.toArray
    if (round == 4) {
      // we need to reorder the byte arrays according to which previous column they belong
      if (column == s) {
        arrays = arrays.sortBy(_._1)
        for (v <- 1 until arrays.length) {
          //println(s"ParseDataPostProcess: round=$round, column $column, old_column=${arrays(v)._1}, array size: ${arrays(v)._2.length}")
          joinArray = joinArray ++ arrays(v)._2
        }
        joinArray = joinArray ++ arrays(0)._2
      } else {
        arrays = arrays.sortBy(_._1)
        for (v <- 0 until arrays.length) {
          //println(s"ParseDataPostProcess: round=$round, column $column, old_column=${arrays(v)._1}, array size: ${arrays(v)._2.length}")
          joinArray = joinArray ++ arrays(v)._2
        }
      }
    } else {
      for (v <- arrays) {
        //println(s"ParseDataPostProcess: round=$round, column $column, old_column=${v._1}, array size: ${v._2.length}")
        joinArray = joinArray ++ v._2
      }
    }

    val ret_array = new Array[(Int, Array[Byte])](1)
    ret_array(0) = (key._1, joinArray)

    ret_array.iterator
  }

  def ColumnSortPreProcess(
    index: Int, data: Iterator[Block], index_offsets: Seq[Int],
    op_code: QEDOpcode, r: Int, s: Int) : Iterator[(Int, Array[Byte])] = {

    val (enclave, eid) = QED.initEnclave()
    var joinArray = new Array[Byte](0)

    // serialize the blocks' bytes
    for (v <- data) {
      joinArray = joinArray ++ v.bytes
    }

    val ret = enclave.EnclaveColumnSort(eid,
      index, s,
      op_code.value, 0, joinArray, r, s, 0, index, 0, index_offsets(index))

    val ret_array = new Array[(Int, Array[Byte])](1)
    ret_array(0) = (0, ret)

    ret_array.iterator
  }

  def ColumnSortPad(data: (Int, Array[Byte]), r: Int, s: Int, op_code: QEDOpcode): (Int, Array[Byte]) = {
    val (enclave, eid) = QED.initEnclave()

    println("ColumnSortPad called============")
    val ret = enclave.EnclaveColumnSort(eid,
      data._1, r,
      op_code.value, 1, data._2, r, s, 0, 0, 0, 0)

    (data._1, ret)
  }

  def ColumnSortPartition(
    input: (Int, Array[Byte]),
    index: Int, numpart: Int,
    op_code: QEDOpcode,
    round: Int, r: Int, s: Int) : (Int, Array[Byte]) = {

    val cur_column = input._1
    val data = input._2

    val (enclave, eid) = QED.initEnclave()
    val ret = enclave.EnclaveColumnSort(eid,
      index, numpart,
      op_code.value, round+1, data, r, s, cur_column, 0, 0, 0)

    (cur_column, ret)
  }

  // The job is to cut off the last x number of rows
  def FinalFilter(column: Int, data: Array[Byte], r: Int, offset: Int, op_code: QEDOpcode) : (Array[Byte], Int) = {
    val (enclave, eid) = QED.initEnclave()

    var num_output_rows = new MutableInteger
    val ret = enclave.ColumnSortFilter(eid, op_code.value, data, column, offset, r, num_output_rows)

    (ret, num_output_rows.value)
  }

  def NewColumnSort(sc: SparkContext, data: RDD[Block], opcode: QEDOpcode, r_input: Int = 0, s_input: Int = 0)
      : RDD[Block] = {
    // parse the bytes and split into blocks, one for each destination column

    val NumMachines = data.partitions.length
    val NumCores = 1

    // let len be N
    // divide N into r * s, where s is the number of machines, and r is the size of the
    // constraints: s | r; r >= 2 * (s-1)^2

    data.cache()

    val numRows = data.mapPartitionsWithIndex((index, x) => CountRows(index, x)).collect.sortBy(_._1)
    var len = 0

    val offsets = ArrayBuffer.empty[Int]

    offsets += 0
    var cur = 0
    for (idx <- 0 until numRows.length) {
      cur += numRows(idx)._2
      offsets += cur
      len += numRows(idx)._2
    }

    for (v <- offsets) {
      println(s"v is $v")
    }

    var s = s_input
    var r = r_input

    if (r_input == 0 && s_input == 0) {
      s = NumMachines * NumCores * Multiplier
      r = (math.ceil(len * 1.0 / s)).toInt
    }

    if (r < 2 * math.pow(s, 2).toInt) {
      r = 2 * math.pow(s, 2).toInt
      logPerf(s"Padding r from $r to ${2 * math.pow(s, 2).toInt}. s=$s, len=$len, r=$r")
    }

    if (r % s != 0) {
      logPerf(s"Padding r from $r to ${(r / s + 1) * s}. s=$s, len=$len, r=$r")
      r = (r / s + 1) * s
    }

    logPerf(s"len=$len, s=$s, r=$r, NumMachines: $NumMachines, NumCores: $NumCores, Multiplier: $Multiplier")

    val parsed_data = data.mapPartitionsWithIndex(
      (index, x) =>
      ColumnSortPreProcess(index, x, offsets, opcode, r, s)
    )
      .flatMap(x => ParseData(x, r, s))
      .groupByKey(s)
      .flatMap(x => ParseDataPostProcess(x, 0, r, s))

    val padded_data = parsed_data.map(x => ColumnSortPad(x, r, s, opcode))

    println("Round 0 done, round 1 begin")

    val data_1 = padded_data.mapPartitionsWithIndex {
      (index, l) => l.toList.map { x =>
        ColumnSortPartition(x, index, s, opcode, 1, r, s)
      }.iterator
    }.flatMap(x => ParseData(x, r, s))
      .groupByKey(s)
      .flatMap(x => ParseDataPostProcess(x, 1, r, s))

    println("Round 1 done, round 2 begin")

    val data_2 = data_1.mapPartitionsWithIndex {
      (index, l) => l.toList.map { x =>
        ColumnSortPartition(x, index, s, opcode, 2, r, s)
      }.iterator
    }.flatMap(x => ParseData(x, r, s))
    .groupByKey(s)
      .flatMap(x => ParseDataPostProcess(x, 2, r, s))

    println("Round 2 done, round 3 begin")

    val data_3 = data_2.mapPartitionsWithIndex {
      (index, l) => l.toList.map { x =>
        ColumnSortPartition(x, index, s, opcode, 3, r, s)
        }.iterator
    }.flatMap(x => ParseData(x, r, s))
      .groupByKey(s)
      .flatMap(x => ParseDataPostProcess(x, 3, r, s))

    println("Round 3 done, round 4 begin")

    val data_4 = data_3.mapPartitionsWithIndex{
      (index, l) => l.toList.map { x =>
        ColumnSortPartition(x, index, s, opcode, 4, r, s)
      }.iterator
    }.flatMap(x => ParseData(x, r, s))
      .groupByKey(s)
      .flatMap(x => ParseDataPostProcess(x, 4, r, s))
      .sortByKey()

    println("Round 4 done")

    val result = data_4.map{x => FinalFilter(x._1, x._2, r, len, opcode)}
      .filter(x => x._1.nonEmpty)
      .map{x => Block(x._1, x._2)}

    result
  }

}
