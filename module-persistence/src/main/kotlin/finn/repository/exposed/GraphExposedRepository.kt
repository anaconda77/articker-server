package finn.repository.exposed

import finn.exception.CriticalDataPollutedException
import finn.queryDto.TickerGraphQueryDto
import finn.table.PredictionTable
import finn.table.TickerPriceTable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.date
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

@Repository
class GraphExposedRepository {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private data class TickerGraphQueryDtoImpl(
        val date: LocalDate,
        val price: BigDecimal,
        val changeRate: BigDecimal,
        val positiveArticleCount: Long,
        val negativeArticleCount: Long
    ) : TickerGraphQueryDto {
        override fun date(): LocalDate = date
        override fun price(): BigDecimal = price
        override fun changeRate(): BigDecimal = changeRate
        override fun positiveArticleCount(): Long = positiveArticleCount
        override fun negativeArticleCount(): Long = negativeArticleCount
    }

    fun findDaily(
        tickerId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<TickerGraphQueryDto> {

        val maxAndCountResult = TickerPriceTable
            .select(
                TickerPriceTable.priceDate.max().date(),
                TickerPriceTable.id.count()
            )
            .where {
                TickerPriceTable.tickerId eq tickerId and
                        TickerPriceTable.priceDate.date().between(
                            startDate,
                            endDate
                        )
            }
            .firstOrNull()

        val count = maxAndCountResult?.get(TickerPriceTable.id.count()) ?: 0L
        val maxDate = maxAndCountResult?.get(TickerPriceTable.priceDate.max().date())
        log.debug { "maxDate: $maxDate, from: $startDate, to: $endDate" }

        // 데이터가 전혀 없는 경우 예외 처리
        if (count == 0L || maxDate == null) {
            throw CriticalDataPollutedException("해당 id로 조회한 데이터가 없습니다, tickerId를 다시 확인해주세요.")
        }

        return TickerPriceTable
            .join(
                PredictionTable,
                JoinType.LEFT,
                additionalConstraint = {
                    (TickerPriceTable.tickerId eq PredictionTable.tickerId) and
                            (TickerPriceTable.priceDate.date() eq PredictionTable.predictionDate.date())
                })
            .select(
                TickerPriceTable.priceDate,
                TickerPriceTable.close,
                TickerPriceTable.changeRate,
                PredictionTable.positiveArticleCount,
                PredictionTable.negativeArticleCount
            )
            .where {
                TickerPriceTable.tickerId eq tickerId and
                        TickerPriceTable.priceDate.date().between(
                            startDate,
                            endDate
                        )
            }
            .orderBy(TickerPriceTable.priceDate, SortOrder.ASC)
            .map { row ->
                TickerGraphQueryDtoImpl(
                    date = row[TickerPriceTable.priceDate].toLocalDate(),
                    price = row[TickerPriceTable.close],
                    changeRate = row[TickerPriceTable.changeRate],
                    positiveArticleCount = row.getOrNull(PredictionTable.positiveArticleCount)
                        ?: 0L,
                    negativeArticleCount = row.getOrNull(PredictionTable.negativeArticleCount) ?: 0L
                )
            }
    }

    /**
     * interval에 의해 등락률 동적 계산
     */
    private data class PriceAndSentimentData(
        val closePrice: BigDecimal,
        val positiveCount: Long,
        val negativeCount: Long
    )

    fun findByInterval(
        tickerId: UUID,
        startDate: LocalDate,
        endDate: LocalDate,
        interval: Int
    ): List<TickerGraphQueryDto> {
        // 3. TickerPrice와 Prediction 테이블을 조인하여 필요한 모든 데이터를 한 번에 조회합니다.
        val dataMap = TickerPriceTable
            .join(
                PredictionTable,
                JoinType.LEFT,
                additionalConstraint = {
                    (TickerPriceTable.tickerId eq PredictionTable.tickerId) and
                            // datetime 컬럼의 날짜 부분만 비교하여 조인합니다.
                            (TickerPriceTable.priceDate.date() eq PredictionTable.predictionDate.date())
                }
            )
            .select(
                TickerPriceTable.priceDate,
                TickerPriceTable.close,
                PredictionTable.positiveArticleCount,
                PredictionTable.negativeArticleCount
            ).where {
                (TickerPriceTable.tickerId eq tickerId) and
                        (TickerPriceTable.priceDate.date().between(
                            startDate.minusDays(interval.toLong()),
                            endDate
                        ))
            }
            .associate { row ->
                row[TickerPriceTable.priceDate].toLocalDate() to PriceAndSentimentData(
                    closePrice = row[TickerPriceTable.close],
                    positiveCount = row.getOrNull(PredictionTable.positiveArticleCount) ?: 0L,
                    negativeCount = row.getOrNull(PredictionTable.negativeArticleCount) ?: 0L
                )
            }

        if (dataMap.isEmpty()) {
            throw CriticalDataPollutedException("해당 id로 조회한 데이터가 없습니다, tickerId를 다시 확인해주세요.")
        }

        val businessDays = dataMap.keys
        val graphData = mutableListOf<TickerGraphQueryDto>()
        var currentDate = endDate

        while (currentDate > startDate) {
            val currentBusinessDay = findClosestBusinessDay(currentDate, businessDays)
            val prevTargetDate = currentDate.minusDays(interval.toLong())
            val prevBusinessDay = findClosestBusinessDay(prevTargetDate, businessDays)

            if (currentBusinessDay != null && prevBusinessDay != null) {
                val currentData = dataMap[currentBusinessDay]
                val prevData = dataMap[prevBusinessDay]

                if (currentData != null && prevData != null && prevData.closePrice != BigDecimal.ZERO) {
                    val changeRate =
                        ((currentData.closePrice / prevData.closePrice) - BigDecimal.ONE).multiply(
                            BigDecimal(100)
                        )

                    // 4. DTO를 생성할 때 Map에서 가져온 count 값들을 추가합니다.
                    graphData.add(
                        TickerGraphQueryDtoImpl(
                            date = currentBusinessDay,
                            price = currentData.closePrice,
                            changeRate = changeRate,
                            positiveArticleCount = currentData.positiveCount,
                            negativeArticleCount = currentData.negativeCount
                        )
                    )
                }
            }
            currentDate = prevTargetDate
        }

        return graphData.sortedBy { it.date() }
    }


    /**
     * 가격 데이터가 존재하는 날짜들(businessDays) 중에서,
     * 주어진 날짜(date)와 같거나 그보다 과거인 가장 최근 날짜를 찾아 반환
     */
    private fun findClosestBusinessDay(date: LocalDate, businessDays: Set<LocalDate>): LocalDate? {
        return businessDays.filter { !it.isAfter(date) }.maxOrNull()
    }

}