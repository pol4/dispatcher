package ot.scalaotl.commands

class OTLReplaceTest extends CommandTest {

  test("Test 0. Command: | replace") {
    val actual = execute("""eval 'j.field' = junkField | replace word with ZZZ in j.field """)
    val expected = """[
                     |{"_time":1568026476854,"_raw":"{\"serialField\": \"0\", \"random_Field\": \"100\", \"WordField\": \"qwe\", \"junkField\": \"q2W\"}","junkField":"q2W","j.field":"q2W"},
                     |{"_time":1568026476854,"_raw":"{\"serialField\": \"1\", \"random_Field\": \"-90\", \"WordField\": \"rty\", \"junkField\": \"132_.\"}","junkField":"132_.","j.field":"132_."},
                     |{"_time":1568026476854,"_raw":"{\"serialField\": \"2\", \"random_Field\": \"50\", \"WordField\": \"uio\", \"junkField\": \"asd.cx\"}","junkField":"asd.cx","j.field":"asd.cx"},
                     |{"_time":1568026476854,"_raw":"{\"serialField\": \"3\", \"random_Field\": \"20\", \"WordField\": \"GreenPeace\", \"junkField\": \"XYZ\"}","junkField":"XYZ","j.field":"XYZ"},
                     |{"_time":1568026476854,"_raw":"{\"serialField\": \"4\", \"random_Field\": \"30\", \"WordField\": \"fgh\", \"junkField\": \"123_ASD\"}","junkField":"123_ASD","j.field":"123_ASD"},
                     |{"_time":1568026476854,"_raw":"{\"serialField\": \"5\", \"random_Field\": \"50\", \"WordField\": \"jkl\", \"junkField\": \"casd(@#)asd\"}","junkField":"casd(@#)asd","j.field":"casd(@#)asd"},
                     |{"_time":1568026476854,"_raw":"{\"serialField\": \"6\", \"random_Field\": \"60\", \"WordField\": \"zxc\", \"junkField\": \"QQQ.2\"}","junkField":"QQQ.2","j.field":"QQQ.2"},
                     |{"_time":1568026476854,"_raw":"{\"serialField\": \"7\", \"random_Field\": \"-100\", \"WordField\": \"RUS\", \"junkField\": \"00_3\"}","junkField":"00_3","j.field":"00_3"},
                     |{"_time":1568026476854,"_raw":"{\"serialField\": \"8\", \"random_Field\": \"0\", \"WordField\": \"MMM\", \"junkField\": \"112\"}","junkField":"112","j.field":"112"},
                     |{"_time":1568026476854,"_raw":"{\"serialField\": \"9\", \"random_Field\": \"10\", \"WordField\": \"USA\", \"junkField\": \"word\"}","junkField":"word","j.field":"ZZZ"}
                     |]""".stripMargin
    assert(jsonCompare(actual, expected), f"Result : $actual\n---\nExpected : $expected")
  }

}