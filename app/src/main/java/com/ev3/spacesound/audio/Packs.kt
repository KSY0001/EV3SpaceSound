package com.ev3.spacesound.audio

enum class Wave { SINE, TRI, SAW, SQUARE }
enum class FilterType { LOWPASS, BANDPASS }

/** Three formant frequencies of a sung vowel. */
enum class Vowel(val f1: Double, val f2: Double, val f3: Double) {
    U(300.0, 870.0, 2240.0), O(450.0, 800.0, 2830.0), A(730.0, 1090.0, 2440.0), E(530.0, 1840.0, 2480.0);
    fun at(k: Int) = when (k) { 0 -> f1; 1 -> f2; else -> f3 }
}

data class DroneVoice(val wave: Wave, val ratio: Double, val gain: Double)
data class Drone(
    val base: Double, val range: Double, val voices: List<DroneVoice>,
    val cut: Double, val cutA: Double, val cutS: Double, val q: Double,
    val gain: Double, val gainA: Double, val breath: Double,
    val drive: Double = 2.2, val rumble: Double = 0.12,
)
data class Pulse(val rate: Double, val rateS: Double, val rateA: Double, val depth: Double)
data class Body(val ratio: Double, val gain: Double, val q: Double)
data class Whine(
    val wave: Wave, val base: Double, val range: Double, val aBend: Double, val rBend: Double, val q: Double,
    val idle: Double, val gain: Double, val gainS: Double, val gainA: Double, val gainR: Double,
    val second: Double, val secondGain: Double,
    val unison: List<Double> = listOf(0.0), val width: Double = 0.0, val body: Body? = null,
    val sat: Double = 0.0, val motionRate: Double = 0.0, val motionDepth: Double = 0.0,
)
data class Choir(
    val voices: Int, val spread: Double, val width: Double, val base: Double, val range: Double,
    val aBend: Double, val rBend: Double, val intervals: List<Pair<Double, Double>>, val from: Vowel, val to: Vowel,
    val q: Double, val idle: Double, val gain: Double, val gainS: Double, val gainA: Double, val gainR: Double,
    val motionRate: Double, val motionDepth: Double, val pulsed: Boolean = false,
)
data class Fm(
    val base: Double, val range: Double, val aBend: Double, val rBend: Double, val ratio: Double,
    val idx: Double, val idxA: Double, val idxS: Double, val idxR: Double, val detune: Double, val width: Double,
    val cut: Double, val cutA: Double, val cutS: Double,
    val idle: Double, val gain: Double, val gainS: Double, val gainA: Double, val gainR: Double,
)
data class Ring(
    val base: Double, val range: Double, val aBend: Double, val rBend: Double, val ratio: Double,
    val cut: Double, val cutA: Double, val cutS: Double, val q: Double, val width: Double,
    val idle: Double, val gain: Double, val gainS: Double, val gainA: Double, val gainR: Double,
)
data class Crackle(val rate: Double, val rateA: Double, val rateR: Double, val rateS: Double, val vol: Double, val lo: Double, val hi: Double)
data class NoiseLayer(
    val filter: FilterType, val base: Double, val fa: Double, val fs: Double, val q: Double,
    val gainA: Double, val gainR: Double, val gainS: Double,
)
data class Pad(
    val wave: Wave, val root: Double, val track: Double, val notes: List<Double>,
    val unison: List<Double> = listOf(0.0), val width: Double = 0.0,
    val cut: Double, val cutA: Double, val cutS: Double, val q: Double,
    val gain: Double, val gainA: Double, val gainS: Double, val attack: Double = 0.0,
    val motionRate: Double = 0.0, val motionDepth: Double = 0.0, val brass: Boolean = false,
)
data class Shepard(
    val voices: Int, val wave: Wave = Wave.SINE, val base: Double, val octaves: Double,
    val rate: Double, val rateA: Double, val rateR: Double,
    val gain: Double, val gainA: Double, val gainR: Double, val gainS: Double, val cut: Double,
)
data class Braam(val th: Double, val root: Double, val notes: List<Double>, val gain: Double, val peak: Double)
data class Spark(val wave: Wave, val scale: List<Double>, val vol: Double, val echo: Double, val fb: Double)
data class Sweep(val f0: Double, val f1: Double, val dur: Double, val vol: Double)
data class Chime(val f: Double, val at: Double, val vol: Double)
data class Boot(val sweep: Sweep?, val braam: Boolean = false, val chime: List<Chime> = emptyList(), val chimeWave: Wave = Wave.SINE)

data class SoundPack(
    val id: String, val name: String, val desc: String,
    val drone: Drone, val pulse: Pulse? = null, val whine: Whine? = null, val choir: Choir? = null,
    val fm: List<Fm> = emptyList(), val ring: Ring? = null, val crackle: Crackle? = null,
    val noise: NoiseLayer? = null, val pads: List<Pad> = emptyList(), val shepard: Shepard? = null,
    val braam: Braam? = null, val spark: Spark? = null,
    val space: Double, val boot: Boot, val shutdown: Sweep,
)

/** The seven packs, kept identical to the browser prototype. */
object Packs {
    private val S = Wave.SINE; private val T = Wave.TRI; private val W = Wave.SAW; private val Q = Wave.SQUARE
    private val BP = FilterType.BANDPASS; private val LP = FilterType.LOWPASS

    private val ionDrone = Drone(42.0, 70.0, listOf(DroneVoice(S, 1.0, 1.0), DroneVoice(T, 1.012, 1.0), DroneVoice(S, 2.0, .3)),
        cut = 250.0, cutA = 900.0, cutS = 300.0, q = .7, gain = .3, gainA = .12, breath = .12, drive = 2.6, rumble = .14)
    private val ionSpark = Spark(S, listOf(1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0, 3136.0), 1.0, .18, .38)
    private val ionBoot = Boot(Sweep(40.0, 900.0, 1.4, .12), chime = listOf(Chime(1568.0, 1.35, .08), Chime(2349.3, 1.49, .07)))

    val all: List<SoundPack> = listOf(
        SoundPack(
            "ion", "이온 드라이브", "기준 팩. 저음 험 위로 터빈 휘슬, 회생 때 충전음",
            drone = ionDrone,
            whine = Whine(W, 180.0, 1700.0, 120.0, -90.0, 6.0, .004, .012, .05, .05, .03, 1.5, .5),
            noise = NoiseLayer(BP, 600.0, 3500.0, 800.0, 1.2, .22, .05, 0.0),
            pads = listOf(Pad(W, 110.0, 1.0, listOf(0.0, 7.0, 12.03), cut = 180.0, cutA = 2600.0, cutS = 500.0, q = 4.0, gain = .035, gainA = .06, gainS = .02)),
            spark = ionSpark, space = 0.0, boot = ionBoot, shutdown = Sweep(900.0, 30.0, 1.6, .1),
        ),
        SoundPack(
            "ion2", "이온 드라이브 II", "고음을 두껍게: 휘슬 5겹 + 옥타브 아래 몸통 톤",
            drone = ionDrone,
            whine = Whine(W, 180.0, 1700.0, 120.0, -90.0, 3.2, .006, .016, .055, .055, .03, 1.5, .4,
                unison = listOf(-14.0, -6.0, 0.0, 6.0, 14.0), width = .7, body = Body(.5, .9, 1.5), sat = 1.8, motionRate = .25, motionDepth = .04),
            noise = NoiseLayer(BP, 600.0, 3500.0, 800.0, 1.2, .2, .05, 0.0),
            pads = listOf(Pad(W, 110.0, 1.0, listOf(0.0, 7.0, 12.03), unison = listOf(-8.0, 0.0, 8.0), width = .6,
                cut = 220.0, cutA = 2600.0, cutS = 600.0, q = 3.0, gain = .04, gainA = .065, gainS = .025)),
            spark = ionSpark, space = .08, boot = ionBoot, shutdown = Sweep(900.0, 30.0, 1.6, .1),
        ),
        SoundPack(
            "photon", "포톤 코러스", "7겹 합창 톤이 \"우~\"에서 \"아~\"로 열리는 두꺼운 중음",
            drone = ionDrone,
            choir = Choir(7, 16.0, .8, 110.0, 165.0, 8.0, -12.0, listOf(0.0 to 1.0, 7.0 to .55, 12.0 to .3), Vowel.U, Vowel.A,
                q = 5.0, idle = .045, gain = .065, gainS = .035, gainA = .075, gainR = .03, motionRate = .15, motionDepth = 80.0),
            noise = NoiseLayer(BP, 600.0, 3000.0, 800.0, 1.2, .12, .04, 0.0),
            spark = ionSpark, space = .18,
            boot = Boot(Sweep(40.0, 900.0, 1.4, .1), chime = listOf(Chime(1568.0, 1.35, .07), Chime(2349.3, 1.49, .06))),
            shutdown = Sweep(900.0, 30.0, 1.6, .09),
        ),
        SoundPack(
            "fm", "FM 터빈", "밟으면 배음이 폭발하듯 늘어나는 금속성 터빈",
            drone = Drone(36.0, 50.0, listOf(DroneVoice(W, 1.0, .45), DroneVoice(S, 1.0, 1.0)),
                cut = 200.0, cutA = 500.0, cutS = 300.0, q = .8, gain = .28, gainA = .12, breath = .05, drive = 3.0, rumble = .16),
            fm = listOf(
                Fm(110.0, 440.0, 30.0, -80.0, 1.5, 1.2, 6.0, 1.5, .5, 6.0, .7, 1600.0, 3200.0, 1200.0, .035, .05, .03, .05, .02),
                Fm(440.0, 1320.0, 60.0, -200.0, 3.01, .6, 2.5, .8, .2, 4.0, .9, 5000.0, 2000.0, 1000.0, .004, .008, .012, .012, .006),
            ),
            noise = NoiseLayer(BP, 1400.0, 2000.0, 1600.0, .9, .12, .08, .05),
            space = .1, boot = Boot(Sweep(50.0, 1400.0, 2.0, .07)), shutdown = Sweep(1400.0, 40.0, 2.6, .07),
        ),
        SoundPack(
            "orch", "오케스트럴", "현악 앙상블과 금관, 셰퍼드 상승, 급가속 브라암",
            drone = Drone(36.7, 18.0, listOf(DroneVoice(S, 1.0, 1.0), DroneVoice(T, 2.0, .35), DroneVoice(W, 1.0, .12)),
                cut = 180.0, cutA = 500.0, cutS = 200.0, q = .8, gain = .32, gainA = .12, breath = .1, drive = 2.4, rumble = .1),
            pads = listOf(
                Pad(W, 73.4, .5, listOf(0.0, 7.0, 12.0, 15.0, 19.0, 24.0), unison = listOf(-14.0, -9.0, -4.0, 0.0, 4.0, 9.0, 14.0), width = .9,
                    cut = 500.0, cutA = 2000.0, cutS = 900.0, q = .7, gain = .028, gainA = .03, gainS = .02, attack = .9, motionRate = .2, motionDepth = 180.0),
                Pad(W, 73.4, .5, listOf(0.0, 7.0, 12.0), unison = listOf(-8.0, 0.0, 8.0), width = .5,
                    cut = 140.0, cutA = 2600.0, cutS = 300.0, q = 2.0, gain = 0.0, gainA = .11, gainS = .012, attack = .25, brass = true),
            ),
            shepard = Shepard(6, W, 73.4, 5.0, .012, .16, .12, .004, .035, .02, .004, 1800.0),
            braam = Braam(.5, 36.7, listOf(0.0, 7.0, 12.0, 15.0), .26, 1500.0),
            spark = Spark(S, listOf(587.3, 698.5, 880.0, 1174.7, 1396.9, 1760.0), .6, .32, .45),
            space = .35,
            boot = Boot(Sweep(36.7, 293.7, 2.2, .04), braam = true, chime = listOf(Chime(587.3, 1.6, .05), Chime(880.0, 1.9, .045))),
            shutdown = Sweep(293.7, 36.7, 2.4, .06),
        ),
        SoundPack(
            "plasma", "플라즈마", "링 모듈레이션 금속 몸통과 지직거리는 방전 입자",
            drone = Drone(45.0, 50.0, listOf(DroneVoice(Q, 1.0, .4), DroneVoice(S, 1.0, 1.0)),
                cut = 260.0, cutA = 600.0, cutS = 300.0, q = .9, gain = .27, gainA = .12, breath = .06, drive = 3.2, rumble = .14),
            ring = Ring(90.0, 360.0, 40.0, -120.0, 2.37, 900.0, 2600.0, 800.0, 1.4, .8, .02, .045, .025, .07, .03),
            whine = Whine(S, 1200.0, 600.0, 0.0, -700.0, 4.0, 0.0, 0.0, .004, 0.0, .03, 1.5, .3),
            crackle = Crackle(1.5, 40.0, 25.0, 4.0, .07, 1800.0, 7000.0),
            noise = NoiseLayer(BP, 3000.0, 3000.0, 1000.0, .7, .05, .04, .01),
            space = .15, boot = Boot(Sweep(40.0, 1200.0, 1.6, .07)), shutdown = Sweep(1200.0, 30.0, 2.0, .07),
        ),
        SoundPack(
            "reactor", "리액터 험", "저음과 중음 합창이 함께 숨 쉬듯 맥동",
            drone = Drone(49.0, 25.0, listOf(DroneVoice(S, 1.0, 1.0), DroneVoice(T, 2.0, .4), DroneVoice(W, 1.0, .15)),
                cut = 320.0, cutA = 600.0, cutS = 300.0, q = .9, gain = .32, gainA = .1, breath = .04, drive = 2.8, rumble = .1),
            pulse = Pulse(.8, 3.5, 1.5, .6),
            choir = Choir(5, 10.0, .7, 98.0, 98.0, 6.0, -10.0, listOf(0.0 to 1.0, 12.0 to .45), Vowel.O, Vowel.E,
                q = 5.0, idle = .05, gain = .06, gainS = .03, gainA = .06, gainR = .02, motionRate = .1, motionDepth = 60.0, pulsed = true),
            noise = NoiseLayer(LP, 350.0, 800.0, 300.0, .7, .08, .04, .02),
            spark = Spark(S, listOf(784.0, 987.8, 1174.7, 1568.0), .5, .25, .5),
            space = .22,
            boot = Boot(Sweep(30.0, 392.0, 2.0, .08), chime = listOf(Chime(392.0, 1.8, .06), Chime(587.3, 2.0, .05))),
            shutdown = Sweep(392.0, 25.0, 2.4, .07),
        ),
    )
}
