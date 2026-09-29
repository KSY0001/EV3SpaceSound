package com.ev3.spacesound.audio

enum class Wave { SINE, TRI, SAW, SQUARE }
enum class FilterType { LOWPASS, BANDPASS }

data class DroneVoice(val wave: Wave, val ratio: Double, val gain: Double)
data class Drone(
    val base: Double, val range: Double, val voices: List<DroneVoice>,
    val cut: Double, val cutA: Double, val cutS: Double, val q: Double,
    val gain: Double, val gainA: Double, val breath: Double,
    /** Saturation amount: adds harmonics so the low drone is heard on car speakers. */
    val drive: Double = 2.2,
    /** Slow random amplitude wobble that gives a big-engine feel. */
    val rumble: Double = 0.12,
)
data class Pulse(val rate: Double, val rateS: Double, val rateA: Double, val depth: Double, val pad: Boolean)
data class Whine(
    val wave: Wave, val filter: FilterType, val base: Double, val range: Double, val aBend: Double, val rBend: Double,
    val q: Double, val idle: Double, val gain: Double, val gainS: Double, val gainA: Double, val gainR: Double,
    val second: Double, val secondGain: Double, val vibRate: Double = 0.0, val vibDepth: Double = 0.0,
)
data class NoiseLayer(
    val filter: FilterType, val base: Double, val fa: Double, val fs: Double, val q: Double,
    val gainA: Double, val gainR: Double, val gainS: Double,
)
data class Pad(
    val wave: Wave, val root: Double, val track: Double, val notes: List<Double>, val unison: List<Double>,
    val cut: Double, val cutA: Double, val cutS: Double, val q: Double,
    val gain: Double, val gainA: Double, val gainS: Double, val attack: Double = 0.0,
)
data class Shepard(
    val voices: Int, val base: Double, val octaves: Double, val rate: Double, val rateA: Double, val rateR: Double,
    val gain: Double, val gainA: Double, val gainR: Double, val gainS: Double, val cut: Double,
)
data class Braam(val th: Double, val root: Double, val notes: List<Double>, val gain: Double, val peak: Double)
data class Spark(val wave: Wave, val scale: List<Double>, val vol: Double, val echo: Double, val fb: Double)
data class Sweep(val f0: Double, val f1: Double, val dur: Double, val vol: Double)
data class Chime(val f: Double, val at: Double, val vol: Double)
data class Boot(val sweep: Sweep?, val braam: Boolean, val chime: List<Chime>, val chimeWave: Wave = Wave.SINE)

data class SoundPack(
    val id: String, val name: String, val desc: String,
    val drone: Drone, val pulse: Pulse? = null, val whine: Whine? = null, val noise: NoiseLayer? = null,
    val pad: Pad? = null, val shepard: Shepard? = null, val braam: Braam? = null, val spark: Spark? = null,
    val space: Double, val boot: Boot, val shutdown: Sweep,
)

/** The six packs, kept identical to the browser prototype. */
object Packs {
    private val S = Wave.SINE; private val T = Wave.TRI; private val W = Wave.SAW; private val Q = Wave.SQUARE
    private val LP = FilterType.LOWPASS; private val BP = FilterType.BANDPASS

    val all: List<SoundPack> = listOf(
        SoundPack(
            "ion", "이온 드라이브", "기본 팩. 저음 험 위로 터빈 휘슬, 회생 때 충전음",
            drone = Drone(42.0, 70.0, listOf(DroneVoice(S, 1.0, 1.0), DroneVoice(T, 1.012, 1.0), DroneVoice(S, 2.0, .3)),
                250.0, 900.0, 300.0, .7, .3, .12, .12, drive = 2.6, rumble = .14),
            whine = Whine(W, BP, 180.0, 1700.0, 120.0, -90.0, 6.0, .004, .012, .05, .05, .03, 1.5, .5),
            noise = NoiseLayer(BP, 600.0, 3500.0, 800.0, 1.2, .22, .05, 0.0),
            pad = Pad(W, 110.0, 1.0, listOf(0.0, 7.0, 12.03), listOf(0.0), 180.0, 2600.0, 500.0, 4.0, .035, .06, .02),
            spark = Spark(S, listOf(1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0, 3136.0), 1.0, .18, .38),
            space = 0.0,
            boot = Boot(Sweep(40.0, 900.0, 1.4, .12), false, listOf(Chime(1568.0, 1.35, .08), Chime(2349.3, 1.49, .07))),
            shutdown = Sweep(900.0, 30.0, 1.6, .1),
        ),
        SoundPack(
            "cine", "시네마틱 (한스 짐머풍)", "D단조 드론과 현악, 셰퍼드 라이저, 급가속 때 브라암",
            drone = Drone(36.7, 18.0, listOf(DroneVoice(S, 1.0, 1.0), DroneVoice(T, 2.0, .35), DroneVoice(S, 3.0, .08)),
                180.0, 500.0, 200.0, .8, .34, .12, .1),
            whine = Whine(S, BP, 293.7, 587.0, 40.0, -60.0, 2.0, 0.0, .004, .012, .01, .01, 1.5, .4),
            noise = NoiseLayer(LP, 300.0, 900.0, 500.0, .6, .06, .05, .02),
            pad = Pad(W, 73.4, .5, listOf(0.0, 7.0, 12.0, 15.0, 19.0), listOf(-9.0, 0.0, 8.0), 220.0, 1500.0, 600.0, 1.2, .03, .035, .02, .9),
            shepard = Shepard(7, 36.7, 7.0, .012, .16, .12, .012, .075, .04, .01, 2400.0),
            braam = Braam(.5, 36.7, listOf(0.0, 7.0, 12.0, 15.0), .2, 1300.0),
            spark = Spark(S, listOf(1174.7, 1396.9, 1568.0, 1760.0, 2093.0, 2349.3), .7, .32, .45),
            space = .3,
            boot = Boot(Sweep(36.7, 293.7, 2.2, .05), true, listOf(Chime(587.3, 1.6, .05), Chime(880.0, 1.9, .045))),
            shutdown = Sweep(293.7, 36.7, 2.4, .07),
        ),
        SoundPack(
            "warp", "워프 코어", "맥동하는 코어 험, 속도 따라 빨라지는 박동",
            drone = Drone(55.0, 35.0, listOf(DroneVoice(S, 1.0, 1.0), DroneVoice(S, 2.0, .5), DroneVoice(T, 3.0, .18)),
                420.0, 700.0, 300.0, 1.0, .3, .08, .05),
            pulse = Pulse(1.1, 6.0, 2.0, .75, true),
            whine = Whine(S, BP, 660.0, 990.0, 60.0, -120.0, 12.0, .004, .006, .012, .01, .01, 2.01, .3),
            noise = NoiseLayer(BP, 1200.0, 2200.0, 600.0, 2.5, .08, .03, .01),
            pad = Pad(T, 110.0, .7, listOf(0.0, 12.0, 19.0), listOf(0.0), 900.0, 1200.0, 400.0, 1.0, .04, .03, .02),
            spark = Spark(S, listOf(1760.0, 2217.5, 2637.0, 3520.0), .8, .12, .5),
            space = .12,
            boot = Boot(Sweep(30.0, 440.0, 1.8, .1), false, listOf(Chime(880.0, 1.7, .06), Chime(1318.5, 1.85, .05))),
            shutdown = Sweep(440.0, 25.0, 2.0, .08),
        ),
        SoundPack(
            "jet", "스텔스 제트", "쌍발 터빈과 거센 바람, 감속 시 스풀다운",
            drone = Drone(30.0, 40.0, listOf(DroneVoice(W, 1.0, .5), DroneVoice(S, 1.0, 1.0)),
                160.0, 400.0, 250.0, .7, .26, .12, .04, drive = 3.0, rumble = .2),
            whine = Whine(W, BP, 400.0, 2600.0, 220.0, -320.0, 8.0, .01, .02, .07, .06, .05, 1.007, .9),
            noise = NoiseLayer(BP, 900.0, 3000.0, 1500.0, .8, .3, .14, .07),
            space = .05,
            boot = Boot(Sweep(60.0, 1600.0, 2.4, .08), false, emptyList()),
            shutdown = Sweep(1600.0, 40.0, 3.0, .08),
        ),
        SoundPack(
            "deep", "딥 스페이스", "조용한 앰비언트 화음과 넓은 공간감",
            drone = Drone(48.0, 30.0, listOf(DroneVoice(S, 1.0, 1.0), DroneVoice(S, 1.5, .25)),
                300.0, 300.0, 150.0, .5, .22, .06, .08, drive = 1.6, rumble = .05),
            whine = Whine(S, BP, 523.3, 523.0, 30.0, -30.0, 3.0, .002, .003, .008, .008, .004, 1.5, .5),
            pad = Pad(T, 130.8, .3, listOf(0.0, 7.0, 12.0, 16.0, 19.0, 24.0), listOf(-5.0, 5.0), 1200.0, 1600.0, 400.0, .7, .028, .02, .012, 1.2),
            shepard = Shepard(5, 65.4, 5.0, .008, .05, .04, .012, .02, .015, .005, 1600.0),
            spark = Spark(S, listOf(1046.5, 1318.5, 1568.0, 2093.0, 2637.0), .8, .42, .55),
            space = .5,
            boot = Boot(Sweep(65.0, 523.0, 2.0, .04), false,
                listOf(Chime(523.3, 1.4, .05), Chime(659.3, 1.6, .045), Chime(784.0, 1.8, .045), Chime(1046.5, 2.0, .04))),
            shutdown = Sweep(523.0, 48.0, 2.4, .04),
        ),
        SoundPack(
            "retro", "레트로 SF", "테레민 같은 멜로디 톤과 8비트 아르페지오",
            drone = Drone(55.0, 30.0, listOf(DroneVoice(Q, 1.0, .5), DroneVoice(S, 1.0, 1.0)),
                380.0, 500.0, 200.0, 1.0, .16, .06, .06, drive = 1.5, rumble = 0.0),
            whine = Whine(S, LP, 220.0, 660.0, 40.0, -60.0, .5, .035, .04, .03, .03, .02, 2.0, .12, vibRate = 5.5, vibDepth = .02),
            spark = Spark(Q, listOf(523.3, 659.3, 784.0, 1046.5, 1318.5, 1568.0), .45, .15, .4),
            space = .15,
            boot = Boot(Sweep(110.0, 880.0, 1.0, .05), false,
                listOf(Chime(523.3, .9, .05), Chime(659.3, 1.0, .05), Chime(784.0, 1.1, .05), Chime(1046.5, 1.2, .05), Chime(1318.5, 1.3, .045)),
                chimeWave = Wave.SQUARE),
            shutdown = Sweep(880.0, 55.0, 1.4, .05),
        ),
    )
}
