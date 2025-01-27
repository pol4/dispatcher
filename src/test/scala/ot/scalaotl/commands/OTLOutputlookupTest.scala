package ot.scalaotl.commands

import scala.io.Source

class OTLOutputlookupTest extends CommandTest {

  test("Test 0. Command: | otoutputlookup ol1") {
    execute("""otoutputlookup ol1""")
    val actual =  Source.fromFile(f"$tmpDir/lookups/ol1").getLines.mkString("\n")
    val expected =
      """_time,_raw
        |1568026476854,"{\"serialField\": \"0\", \"random_Field\": \"100\", \"WordField\": \"qwe\", \"junkField\": \"q2W\"}"
        |1568026476855,"{\"serialField\": \"1\", \"random_Field\": \"-90\", \"WordField\": \"rty\", \"junkField\": \"132_.\"}"
        |1568026476856,"{\"serialField\": \"2\", \"random_Field\": \"50\", \"WordField\": \"uio\", \"junkField\": \"asd.cx\"}"
        |1568026476857,"{\"serialField\": \"3\", \"random_Field\": \"20\", \"WordField\": \"GreenPeace\", \"junkField\": \"XYZ\"}"
        |1568026476858,"{\"serialField\": \"4\", \"random_Field\": \"30\", \"WordField\": \"fgh\", \"junkField\": \"123_ASD\"}"
        |1568026476859,"{\"serialField\": \"5\", \"random_Field\": \"50\", \"WordField\": \"jkl\", \"junkField\": \"casd(@#)asd\"}"
        |1568026476860,"{\"serialField\": \"6\", \"random_Field\": \"60\", \"WordField\": \"zxc\", \"junkField\": \"QQQ.2\"}"
        |1568026476861,"{\"serialField\": \"7\", \"random_Field\": \"-100\", \"WordField\": \"RUS\", \"junkField\": \"00_3\"}"
        |1568026476862,"{\"serialField\": \"8\", \"random_Field\": \"0\", \"WordField\": \"MMM\", \"junkField\": \"112\"}"
        |1568026476863,"{\"serialField\": \"9\", \"random_Field\": \"10\", \"WordField\": \"USA\", \"junkField\": \"word\"}"""".stripMargin
    assert(actual == expected, f"Result : $actual\n---\nExpected : $expected")
  }

  ignore("Test 1. Command: | otoutputlookup ol2 append=True") {
    val exisiting_lookup= """_raw,_time
                            |"{\"serialField\": \"11\", \"random_Field\": \"100\", \"WordField\": \"qwe\", \"junkField\": \"q2W\"}",1568026476854""".stripMargin
    writeTextFile(exisiting_lookup,"/lookups/ol2")
    execute("""otoutputlookup ol2 append=true """)
    val actual =  Source.fromFile(f"$tmpDir/lookups/ol2").getLines.mkString("\n")
    val expected =
      """_time,_raw
        |1568026476854,"{\"serialField\": \"0\", \"random_Field\": \"100\", \"WordField\": \"qwe\", \"junkField\": \"q2W\"}"
        |1568026476855,"{\"serialField\": \"1\", \"random_Field\": \"-90\", \"WordField\": \"rty\", \"junkField\": \"132_.\"}"
        |1568026476856,"{\"serialField\": \"2\", \"random_Field\": \"50\", \"WordField\": \"uio\", \"junkField\": \"asd.cx\"}"
        |1568026476857,"{\"serialField\": \"3\", \"random_Field\": \"20\", \"WordField\": \"GreenPeace\", \"junkField\": \"XYZ\"}"
        |1568026476858,"{\"serialField\": \"4\", \"random_Field\": \"30\", \"WordField\": \"fgh\", \"junkField\": \"123_ASD\"}"
        |1568026476859,"{\"serialField\": \"5\", \"random_Field\": \"50\", \"WordField\": \"jkl\", \"junkField\": \"casd(@#)asd\"}"
        |1568026476860,"{\"serialField\": \"6\", \"random_Field\": \"60\", \"WordField\": \"zxc\", \"junkField\": \"QQQ.2\"}"
        |1568026476861,"{\"serialField\": \"7\", \"random_Field\": \"-100\", \"WordField\": \"RUS\", \"junkField\": \"00_3\"}"
        |1568026476862,"{\"serialField\": \"8\", \"random_Field\": \"0\", \"WordField\": \"MMM\", \"junkField\": \"112\"}"
        |1568026476863,"{\"serialField\": \"9\", \"random_Field\": \"10\", \"WordField\": \"USA\", \"junkField\": \"word\"}"
        |1568026476864,"{\"serialField\": \"11\", \"random_Field\": \"100\", \"WordField\": \"qwe\", \"junkField\": \"q2W\"}"""".stripMargin
    assert(actual == expected, f"Result : $actual\n---\nExpected : $expected")
  }

}
