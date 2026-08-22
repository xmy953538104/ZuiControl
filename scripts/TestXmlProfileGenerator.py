#!/usr/bin/env python3
import pathlib
import subprocess
import tempfile
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/zui/zuicontrol/XmlProfileGenerator.java"
DEFAULT_GAME = ROOT / "payload/system/etc/zui_control/default_game_policy.xml"
DEFAULT_PERF = ROOT / "payload/system/etc/zui_control/default_performanceconfig.xml"


def checked(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True)


def main():
    with tempfile.TemporaryDirectory(prefix="zuicontrol_xml_test_") as temp_name:
        temp = pathlib.Path(temp_name)
        classes = temp / "classes"
        classes.mkdir()
        profiles = temp / "profiles.prop"
        output_game = temp / "game_policy.xml"
        output_perf = temp / "performanceconfig.xml"
        profiles.write_text(
            "com.example.game|powersave|v4|independent|default|"
            "-1000,1017600,460800,1401600,614400,1401600,614400,1478400,576000,500000,310000\n"
            "com.example.game|balanced|v4|independent|default|"
            "-1000,1804800,672000,2438400,844800,2323200,844800,3302400,902400,903000,422000;"
            "8,1574400,556800,2035200,729600,1920000,729600,2112000,787200,578000,310000;"
            "14,1248000,460800,1708800,614400,1612800,614400,1708800,576000,422000,310000\n",
            encoding="utf-8",
        )

        checked("javac", "-encoding", "UTF-8", "-d", str(classes), str(SOURCE))
        result = checked(
            "java",
            "-cp",
            str(classes),
            "com.zui.zuicontrol.XmlProfileGenerator",
            str(DEFAULT_GAME),
            str(DEFAULT_PERF),
            str(profiles),
            str(output_game),
            str(output_perf),
        )
        assert "profiles=1" in result.stdout
        assert "mirrored_modes=balanced,powersave,savage" in result.stdout

        game = ET.parse(output_game)
        app = game.find(".//App[@pkg='com.example.game']")
        assert app is not None
        limit = app.find("./Attribute[@name='LimitConfig']")
        assert limit is not None and limit.text
        modes = limit.text.strip().split()
        assert len(modes) == 3 and modes[0] == modes[1] == modes[2]

        perf = ET.parse(output_perf)
        type_levels = {}
        for type_node in perf.findall(".//GameLimitConfig/Type"):
            type_levels[type_node.get("name")] = {
                freq.get("level"): (freq.text or "").strip()
                for freq in type_node.findall("./Freq")
            }
        for segment in modes[0].split("|"):
            ids = segment.split(":", 1)[1].split("_")
            assert len(ids) == 5
            assert len(set(ids[:4])) == 1
            shared_id = ids[0]
            assert int(shared_id) >= 900000
            for type_name in ("LittleCore", "BigCore", "TitanCore", "MegaCore"):
                assert shared_id in type_levels[type_name]

        first_id = modes[0].split("|", 1)[0].split(":", 1)[1].split("_", 1)[0]
        assert type_levels["LittleCore"][first_id] == "1804800_672000_-1"
        assert type_levels["BigCore"][first_id] == "2438400_844800_-1"
        assert type_levels["TitanCore"][first_id] == "2323200_844800_-1"
        assert type_levels["MegaCore"][first_id] == "-1_902400_-1"
        first_ids = modes[0].split("|", 1)[0].split(":", 1)[1].split("_")
        gpu_id = first_ids[4]
        assert gpu_id == "901"
        assert type_levels["GPU"][gpu_id] == "8_0_-1"

    print("XmlProfileGenerator shared CPU levels and mirrored OEM modes: OK")


if __name__ == "__main__":
    main()
