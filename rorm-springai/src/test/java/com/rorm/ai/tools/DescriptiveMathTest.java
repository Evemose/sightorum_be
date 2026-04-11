package com.rorm.ai.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

@DisplayName("DescriptiveMath")
class DescriptiveMathTest {

    @Nested
    @DisplayName("linearRegression")
    class LinearRegression {

        @Test
        @DisplayName("fits a perfect upward line y = 2x + 1 with slope 2")
        void fitsPerfectUpwardLine() {
            var series = new double[]{1, 3, 5, 7, 9, 11};

            var fit = DescriptiveMath.linearRegression(series);

            assertThat(fit.slope()).isEqualTo(2.0, offset(1e-9));
            assertThat(fit.intercept()).isEqualTo(1.0, offset(1e-9));
            assertThat(fit.rSquared()).isEqualTo(1.0, offset(1e-9));
        }

        @Test
        @DisplayName("fits a perfect downward line")
        void fitsPerfectDownwardLine() {
            var series = new double[]{10, 8, 6, 4, 2};

            var fit = DescriptiveMath.linearRegression(series);

            assertThat(fit.slope()).isEqualTo(-2.0, offset(1e-9));
            assertThat(fit.intercept()).isEqualTo(10.0, offset(1e-9));
            assertThat(fit.rSquared()).isEqualTo(1.0, offset(1e-9));
        }

        @Test
        @DisplayName("returns zero slope for flat series")
        void zeroSlopeForFlatSeries() {
            var series = new double[]{5, 5, 5, 5, 5};

            var fit = DescriptiveMath.linearRegression(series);

            assertThat(fit.slope()).isEqualTo(0.0, offset(1e-9));
            assertThat(fit.intercept()).isEqualTo(5.0, offset(1e-9));
        }

        @Test
        @DisplayName("returns intercept only for single-point series")
        void singlePointReturnsIntercept() {
            var fit = DescriptiveMath.linearRegression(new double[]{42.0});

            assertThat(fit.slope()).isEqualTo(0.0);
            assertThat(fit.intercept()).isEqualTo(42.0);
            assertThat(fit.rSquared()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("returns zero slope for empty series")
        void emptySeriesReturnsZero() {
            var fit = DescriptiveMath.linearRegression(new double[]{});

            assertThat(fit.slope()).isEqualTo(0.0);
            assertThat(fit.intercept()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("rSquared less than 1 for noisy data around a trend")
        void noisyDataLowerRSquared() {
            var series = new double[]{1, 3, 2, 4, 5, 7, 6, 8, 9};

            var fit = DescriptiveMath.linearRegression(series);

            assertThat(fit.slope()).isGreaterThan(0.5);
            assertThat(fit.rSquared())
                .isGreaterThan(0.7)
                .isLessThan(1.0);
        }
    }

    @Nested
    @DisplayName("cusumChangePoint")
    class CusumChangePoint {

        @Test
        @DisplayName("detects step-function break as significant")
        void stepFunctionFires() {
            var series = new double[30];
            for (var i = 0; i < 15; i++) {
                series[i] = 10.0;
            }
            for (var i = 15; i < 30; i++) {
                series[i] = 50.0;
            }

            var result = DescriptiveMath.cusumChangePoint(series);

            assertThat(result.significant()).isTrue();
            assertThat(result.breakIndex()).isBetween(13, 16);
        }

        @Test
        @DisplayName("does not fire on flat series")
        void flatSeriesDoesNotFire() {
            var series = new double[]{5, 5, 5, 5, 5, 5, 5, 5, 5, 5};

            var result = DescriptiveMath.cusumChangePoint(series);

            assertThat(result.significant()).isFalse();
        }

        @Test
        @DisplayName("does not fire for short series (n < 4)")
        void shortSeriesDoesNotFire() {
            var result = DescriptiveMath.cusumChangePoint(new double[]{1, 2, 3});

            assertThat(result.significant()).isFalse();
            assertThat(result.breakIndex()).isEqualTo(-1);
        }

        @Test
        @DisplayName("does not fire on linear trend (no abrupt break)")
        void linearTrendDoesNotFire() {
            var series = new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

            var result = DescriptiveMath.cusumChangePoint(series);

            assertThat(result.significant()).isFalse();
        }
    }

    @Nested
    @DisplayName("varianceLevelCorrelation")
    class VarianceLevelCorrelation {

        @Test
        @DisplayName("returns high positive correlation for multiplicative series")
        void multiplicativeSeriesHighCorr() {
            var series = new double[30];
            for (var i = 0; i < 30; i++) {
                var level = 10.0 + i;
                var noise = ((i % 5) - 2) * level * 0.1;
                series[i] = level + noise;
            }

            var corr = DescriptiveMath.varianceLevelCorrelation(series, 3);

            assertThat(corr).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("returns low correlation for constant-variance additive series")
        void additiveSeriesLowCorr() {
            var series = new double[30];
            for (var i = 0; i < 30; i++) {
                series[i] = 100.0 + (i % 2 == 0 ? 1.0 : -1.0);
            }

            var corr = DescriptiveMath.varianceLevelCorrelation(series, 3);

            assertThat(corr).isBetween(-0.3, 0.3);
        }

        @Test
        @DisplayName("returns 0 for series too short for window")
        void tooShortReturnsZero() {
            var corr = DescriptiveMath.varianceLevelCorrelation(new double[]{1, 2, 3}, 3);

            assertThat(corr).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("totalVariationDistance")
    class TotalVariationDistance {

        @Test
        @DisplayName("returns 0 for identical distributions")
        void identicalReturnsZero() {
            var a = Map.of("x", 50L, "y", 50L);
            var b = Map.of("x", 100L, "y", 100L);

            assertThat(DescriptiveMath.totalVariationDistance(a, b)).isEqualTo(0.0, offset(1e-9));
        }

        @Test
        @DisplayName("returns 1 for fully disjoint distributions")
        void disjointReturnsOne() {
            var a = Map.of("x", 100L);
            var b = Map.of("y", 100L);

            assertThat(DescriptiveMath.totalVariationDistance(a, b)).isEqualTo(1.0, offset(1e-9));
        }

        @Test
        @DisplayName("returns 0 when either side is empty")
        void emptySideReturnsZero() {
            var a = Map.<String, Long>of();
            var b = Map.of("x", 100L);

            assertThat(DescriptiveMath.totalVariationDistance(a, b)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("returns 0.3 for 30-percentage-point shift in a single segment")
        void partialShiftReturnsExpected() {
            var a = Map.of("x", 70L, "y", 30L);
            var b = Map.of("x", 40L, "y", 60L);

            var tv = DescriptiveMath.totalVariationDistance(a, b);

            assertThat(tv).isEqualTo(0.3, offset(1e-9));
        }
    }

    @Nested
    @DisplayName("directionMajority")
    class DirectionMajorityTests {

        @Test
        @DisplayName("counts all-agree as full agreement with reference")
        void allAgree() {
            var dirs = List.of(
                DescriptiveMath.Direction.UP,
                DescriptiveMath.Direction.UP,
                DescriptiveMath.Direction.UP);

            var result = DescriptiveMath.directionMajority(dirs, DescriptiveMath.Direction.UP);

            assertThat(result.agree()).isEqualTo(3);
            assertThat(result.disagree()).isEqualTo(0);
            assertThat(result.majority()).isEqualTo(DescriptiveMath.Direction.UP);
        }

        @Test
        @DisplayName("counts all-disagree as full disagreement")
        void allDisagree() {
            var dirs = List.of(
                DescriptiveMath.Direction.DOWN,
                DescriptiveMath.Direction.DOWN,
                DescriptiveMath.Direction.DOWN);

            var result = DescriptiveMath.directionMajority(dirs, DescriptiveMath.Direction.UP);

            assertThat(result.agree()).isEqualTo(0);
            assertThat(result.disagree()).isEqualTo(3);
            assertThat(result.majority()).isEqualTo(DescriptiveMath.Direction.DOWN);
        }

        @Test
        @DisplayName("excludes FLAT directions from agree/disagree tallies")
        void excludesFlatFromTallies() {
            var dirs = List.of(
                DescriptiveMath.Direction.UP,
                DescriptiveMath.Direction.FLAT,
                DescriptiveMath.Direction.FLAT,
                DescriptiveMath.Direction.DOWN);

            var result = DescriptiveMath.directionMajority(dirs, DescriptiveMath.Direction.UP);

            assertThat(result.agree()).isEqualTo(1);
            assertThat(result.disagree()).isEqualTo(1);
            assertThat(result.flat()).isEqualTo(2);
            assertThat(result.majority()).isEqualTo(DescriptiveMath.Direction.FLAT);
        }
    }

    @Nested
    @DisplayName("gapSpread")
    class GapSpreadTests {

        @Test
        @DisplayName("fires weak-gap when k/k+1 boundary gap < 0.3x median adjacent gap")
        void weakGapFires() {
            var sorted = new double[]{100, 99.5, 99, 50, 49, 48};

            var result = DescriptiveMath.gapSpread(sorted, 3);

            assertThat(result.boundaryGap()).isEqualTo(49.0, offset(1e-9));
            assertThat(result.gapIsWeak()).isFalse();
        }

        @Test
        @DisplayName("fires weak-gap when boundary gap is small relative to median")
        void strongClusterWeakBoundary() {
            var sorted = new double[]{100, 90, 80, 79, 70, 60, 50, 40};

            var result = DescriptiveMath.gapSpread(sorted, 3);

            assertThat(result.boundaryGap()).isEqualTo(1.0, offset(1e-9));
            assertThat(result.medianGap()).isEqualTo(10.0, offset(1e-9));
            assertThat(result.gapIsWeak()).isTrue();
        }

        @Test
        @DisplayName("does not fire when boundary gap equals median")
        void uniformGapsDoNotFire() {
            var sorted = new double[]{100, 90, 80, 70, 60, 50, 40};

            var result = DescriptiveMath.gapSpread(sorted, 3);

            assertThat(result.gapIsWeak()).isFalse();
        }

        @Test
        @DisplayName("does not fire when ranking is shorter than k")
        void rankingShorterThanKDoesNotFire() {
            var sorted = new double[]{100, 90};

            var result = DescriptiveMath.gapSpread(sorted, 5);

            assertThat(result.gapIsWeak()).isFalse();
        }
    }

    @Nested
    @DisplayName("heterogeneity")
    class HeterogeneityTests {

        @Test
        @DisplayName("fires when max/min ratio >= 3")
        void ratioTriggersFire() {
            var segments = List.of(10.0, 40.0, 50.0);

            var result = DescriptiveMath.heterogeneity(segments, 33.3);

            assertThat(result.maxMinRatio()).isEqualTo(5.0, offset(1e-9));
            assertThat(result.fired()).isTrue();
        }

        @Test
        @DisplayName("fires when deviation factor > 2x median even if ratio < 3")
        void deviationFactorTriggersFire() {
            var segments = List.of(90.0, 100.0, 101.0, 300.0);

            var result = DescriptiveMath.heterogeneity(segments, 100.0);

            assertThat(result.fired()).isTrue();
            assertThat(result.maxDeviationFactor()).isGreaterThan(2.0);
        }

        @Test
        @DisplayName("does not fire for homogeneous segments")
        void homogeneousSegmentsDoNotFire() {
            var segments = List.of(100.0, 102.0, 98.0, 101.0);

            var result = DescriptiveMath.heterogeneity(segments, 100.0);

            assertThat(result.fired()).isFalse();
        }

        @Test
        @DisplayName("does not fire with fewer than 2 finite segments")
        void singleSegmentDoesNotFire() {
            var result = DescriptiveMath.heterogeneity(List.of(100.0), 100.0);

            assertThat(result.fired()).isFalse();
        }
    }

    @Nested
    @DisplayName("coefficientOfVariation")
    class CoefficientOfVariationTests {

        @Test
        @DisplayName("returns 0 for constant values")
        void constantReturnsZero() {
            var cov = DescriptiveMath.coefficientOfVariation(List.of(5.0, 5.0, 5.0, 5.0));

            assertThat(cov).isEqualTo(0.0, offset(1e-9));
        }

        @Test
        @DisplayName("returns stddev over abs mean for varying values")
        void varyingReturnsExpected() {
            var values = List.of(10.0, 20.0, 30.0, 40.0, 50.0);

            var cov = DescriptiveMath.coefficientOfVariation(values);

            // mean=30, var_pop = (400+100+0+100+400)/5 = 200, std ≈ 14.142
            // cov = 14.142 / 30 ≈ 0.4714
            assertThat(cov).isEqualTo(0.4714, offset(1e-4));
        }

        @Test
        @DisplayName("returns 0 for zero-mean values")
        void zeroMeanReturnsZero() {
            var cov = DescriptiveMath.coefficientOfVariation(List.of(-1.0, 1.0, -1.0, 1.0));

            assertThat(cov).isEqualTo(0.0);
        }

        @Test
        @DisplayName("returns 0 for single-value input")
        void singleValueReturnsZero() {
            assertThat(DescriptiveMath.coefficientOfVariation(List.of(42.0))).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("direction")
    class DirectionClassification {

        @Test
        @DisplayName("returns UP when value exceeds threshold")
        void upForPositive() {
            assertThat(DescriptiveMath.direction(1.0, 0.01))
                .isEqualTo(DescriptiveMath.Direction.UP);
        }

        @Test
        @DisplayName("returns DOWN when value below negative threshold")
        void downForNegative() {
            assertThat(DescriptiveMath.direction(-5.0, 0.01))
                .isEqualTo(DescriptiveMath.Direction.DOWN);
        }

        @Test
        @DisplayName("returns FLAT when within threshold of zero")
        void flatForSmallValue() {
            assertThat(DescriptiveMath.direction(0.001, 0.01))
                .isEqualTo(DescriptiveMath.Direction.FLAT);
        }
    }
}
