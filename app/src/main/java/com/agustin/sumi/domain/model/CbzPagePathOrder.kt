package com.agustin.sumi.domain.model

/** Natural order for normalized, slash-separated CBZ paths. */
internal object CbzPagePathOrder : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        var leftStart = 0
        var rightStart = 0
        while (true) {
            val leftEnd = left.indexOf('/', leftStart).let { if (it < 0) left.length else it }
            val rightEnd = right.indexOf('/', rightStart).let { if (it < 0) right.length else it }
            val order = compareSegment(left, leftStart, leftEnd, right, rightStart, rightEnd)
            if (order != 0) return order

            val leftDone = leftEnd == left.length
            val rightDone = rightEnd == right.length
            if (leftDone && rightDone) return left.compareTo(right)
            if (leftDone || rightDone) return if (leftDone) -1 else 1
            leftStart = leftEnd + 1
            rightStart = rightEnd + 1
        }
    }

    private fun compareSegment(
        left: String,
        leftStart: Int,
        leftEnd: Int,
        right: String,
        rightStart: Int,
        rightEnd: Int,
    ): Int {
        var a = leftStart
        var b = rightStart
        while (a < leftEnd && b < rightEnd) {
            if (left[a] in '0'..'9' && right[b] in '0'..'9') {
                val aEnd = digitEnd(left, a, leftEnd)
                val bEnd = digitEnd(right, b, rightEnd)
                val order = compareDigits(left, a, aEnd, right, b, bEnd)
                if (order != 0) return order
                a = aEnd
                b = bEnd
            } else {
                val order = left[a].uppercaseChar().lowercaseChar()
                    .compareTo(right[b].uppercaseChar().lowercaseChar())
                if (order != 0) return order
                a++
                b++
            }
        }
        return (leftEnd - a).compareTo(rightEnd - b)
    }

    private fun digitEnd(path: String, start: Int, end: Int): Int {
        var index = start
        while (index < end && path[index] in '0'..'9') index++
        return index
    }

    private fun compareDigits(
        left: String,
        leftStart: Int,
        leftEnd: Int,
        right: String,
        rightStart: Int,
        rightEnd: Int,
    ): Int {
        var a = leftStart
        var b = rightStart
        while (a < leftEnd && left[a] == '0') a++
        while (b < rightEnd && right[b] == '0') b++

        val lengthOrder = (leftEnd - a).compareTo(rightEnd - b)
        if (lengthOrder != 0) return lengthOrder
        while (a < leftEnd) {
            val order = left[a++].compareTo(right[b++])
            if (order != 0) return order
        }
        return (leftEnd - leftStart).compareTo(rightEnd - rightStart)
    }
}
