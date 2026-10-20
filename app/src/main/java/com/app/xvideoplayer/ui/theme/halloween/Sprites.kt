package com.app.xvideoplayer.ui.theme.halloween

/** Pixel-art Halloween sprites. Each row is a string; each char maps to a palette color ('.' = transparent). */
object Sprites {
    val palette: Map<Char, Long> = mapOf(
        'o' to 0xFFFF7A18,
        'O' to 0xFFC24E00,
        'd' to 0xFF7A2E00,
        'y' to 0xFFFFD54A,
        'Y' to 0xFFFFF3B0,
        'g' to 0xFF5BD44A,
        'G' to 0xFF2E7D32,
        'w' to 0xFFF4F0FF,
        's' to 0xFFB9A8E0,
        'k' to 0xFF140A1C,
        'p' to 0xFF8B3DFF,
        'P' to 0xFF4B1C99,
        'r' to 0xFFFF2E4D,
        'R' to 0xFF9E0F2A,
        'b' to 0xFF7A4FB0,
        'B' to 0xFF3E2366,
        'e' to 0xFFFF2E4D,
        'c' to 0xFF7CFF4F,
        'C' to 0xFF2FA81C,
        'm' to 0xFFFFF7D6,
        'M' to 0xFFE8D9A0,
        'n' to 0xFF6E6E80,
        'N' to 0xFF3F3F4F,
        'l' to 0xFFA0A0B8,
        'h' to 0xFF5A3A1A
    )

    val pumpkin = Sprite("pumpkin", listOf(
        ".......GG.......",
        "......GgG.......",
        "...OOOoGGoOOO...",
        "..OoooOooOoooO..",
        ".OoooOooooOoooO.",
        ".OooyyOooOyyooO.",
        "OooyyyyOOyyyyooO",
        "OoooooOooOoooooO",
        "OoooooOyyOoooooO",
        "OoyooOooooOooyoO",
        "OoyyyyyyyyyyyyoO",
        ".OoyyOyyyyOyyoO.",
        ".OoooOOyyOOoooO.",
        "..OOooOooOooOO..",
        "....OOOOOOOO...."
    ))

    val ghost = Sprite("ghost", listOf(
        ".....wwwww......",
        "...wwwwwwwws....",
        "..wwwwwwwwwss...",
        ".wwwwwwwwwwwss..",
        ".wwkkwwwkkwwss..",
        ".wwkkwwwkkwwss..",
        "wwwwwwwwwwwwwss.",
        "wwwwwwkkkwwwwss.",
        "wwwwwkkkkkwwwss.",
        "wwwwwwkkkwwwwss.",
        "wwwwwwwwwwwwwss.",
        "wwwwwwwwwwwwwss.",
        "wwwwwwwwwwwwwss.",
        "ww.wwww.wwww.ss.",
        "w...ww...ww...s."
    ))

    val bat1 = Sprite("bat1", listOf(
        "B..............B",
        "BB............BB",
        "BbB....bb....BbB",
        "BbbB..bBBb..BbbB",
        "BbbbBbBBBBbBbbbB",
        ".BbbbBBeBeBBbbB.",
        "..BBbBBBBBBbBB..",
        "....B.BBBB.B....",
        ".......BB......."
    ))

    val bat2 = Sprite("bat2", listOf(
        "................",
        "................",
        ".......bb.......",
        "......bBBb......",
        ".....BBBBBB.....",
        "..BBBBBeBeBBBB..",
        ".BbbbBBBBBBbbbB.",
        "BbbB.B.BB.B.BbbB",
        "BB......BB....BB"
    ))

    val skull = Sprite("skull", listOf(
        "....mmmmmm....",
        "..mmmmmmmmmm..",
        ".mmmmmmmmmmmM.",
        ".mmmmmmmmmmmM.",
        "mmkkkmmmmkkkmM",
        "mmkkkmmmmkkkmM",
        "mmkkkmmmmkkkmM",
        ".mmmmmkkmmmmM.",
        "..mmmmkkmmmM..",
        "...mmmmmmmM...",
        "...mkmkmkmM...",
        "...mmmmmmmM...",
        "....MMMMMM...."
    ))

    val spider = Sprite("spider", listOf(
        ".......l.......",
        ".......l.......",
        ".......l.......",
        ".......l.......",
        "..P...PPP...P..",
        "...P.PpppP.P...",
        "P...PppppppP..P",
        ".PPPPpepepPPPP.",
        "....PpppppP....",
        ".PPPPPpppPPPPP.",
        "P...PPpppPP...P",
        "...P.PPPPP.P...",
        "..P...PPP...P.."
    ))

    val candy = Sprite("candy", listOf(
        "r.r..........r.r",
        ".rRr.rrrrrr.rRr.",
        "..rrrrwrrwrrrr..",
        "..rrrwrrwrrwrr..",
        ".rRr.rrrrrr.rRr.",
        "r.r..........r.r"
    ))

    val cauldron = Sprite("cauldron", listOf(
        "...c..c...c...",
        "..c.cccc.c.c..",
        ".CcccccccccccC",
        "NNNNNNNNNNNNNN",
        ".nnNnnnnnnnnN.",
        "nnlnnnnnnnnnnN",
        "nlnnnnnnnnnnnN",
        "nnnnnnnnnnnnnN",
        ".nnnnnnnnnnnN.",
        "..nnnnnnnnnN..",
        "..NN......NN.."
    ))

    val moon = Sprite("moon", listOf(
        "....YYYYY....",
        "..YYYYYYYYY..",
        ".YYYYyYYYYYY.",
        ".YYYyyYYYYYYY",
        "YYYYYYYYYYYYy",
        "YYYYYYYYyYYYy",
        "YYYYYYYyyYYYy",
        "YYyYYYYYYYYYy",
        ".YyyYYYYYYYy.",
        ".YYYYYYYYYyy.",
        "..YYYYYYYyy..",
        "....YYyyy...."
    ))

    val tomb = Sprite("tomb", listOf(
        "....nnnnnn....",
        "..nnllllllnn..",
        ".nlllllllllln.",
        ".nllllnnllllnN",
        "nllllllnllllnN",
        "nllllnnnnnllnN",
        "nllllllnllllnN",
        "nllllllnllllnN",
        "nlllllllllllnN",
        "nllllllllllnnN",
        "nlllllllllllnN",
        "GgGgGGgGGgGgGG"
    ))

    val hat = Sprite("hat", listOf(
        ".........PP.....",
        "........PpP.....",
        ".......PppP.....",
        "......PpppP.....",
        ".....PppppP.....",
        "....PpppppP.....",
        "....PppppppP....",
        "...PyyyyyyyyP...",
        "...PPPPPPPPPP...",
        "PPPPppppppppPPPP",
        ".PPPPPPPPPPPPPP."
    ))

    val folder = Sprite("folder", listOf(
        ".hhhhh..........",
        "hdddddh.........",
        "hddddddhhhhhhhh.",
        "hdoooooooooooodh",
        "hdoyoooooooooodh",
        "hdooooooooooooOh",
        "hdoooooooooooOOh",
        "hdooooooooooOOOh",
        "hdoooooooooOOOOh",
        "hddddddddddddddh",
        ".hhhhhhhhhhhhhh."
    ))

}

class Sprite(val id: String, val rows: List<String>) {
    val width: Int = rows.maxOf { it.length }
    val height: Int = rows.size
}
