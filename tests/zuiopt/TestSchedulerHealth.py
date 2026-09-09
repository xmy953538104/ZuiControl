"""Compile and execute the production health methods against property fixtures."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root/'framework_patch/src/services/com/zui/server/control/ZuiControlService.java').read_text(encoding='utf8')
methods = source[source.index('    private String schedulerHealthStateLines()'):source.index('    private String profileStateLines()')]
constants = '\n'.join(line for line in source.splitlines() if 'private static final String PROP_' in line and any(name in line for name in ('SCHEDULER_ACTIVE','UPERF_SERVICE','UPERF_MODE','UPERF_FAIL_SAFE','ZUIOPT_SERVICE','ZUIOPT_FAILED')))
fixture = '''import java.util.*;
public class HealthFixture {
    static class SystemProperties {
        static Map<String,String> values = new HashMap<>();
        static String get(String key, String fallback) { return values.getOrDefault(key, fallback); }
    }
    String mLastSchedulerError = "";
    static class SceneStatus { String stateLines(){return "\\nzuioptSceneSeq=12\\nzuioptSceneAck=11\\nzuioptSceneSync=pending";} }
    SceneStatus mZuioptScene = new SceneStatus();
''' + constants + methods + '''
    static void check(String active, String failed, String service, String uperf, String health, String state) {
        Map<String,String> p = SystemProperties.values;
        p.put("sys.boot_completed", "1"); p.put(PROP_SCHEDULER_ACTIVE, active);
        p.put(PROP_ZUIOPT_FAILED, failed); p.put(PROP_ZUIOPT_SERVICE, service);
        p.put(PROP_UPERF_SERVICE, uperf); p.put(PROP_UPERF_MODE, "balance"); p.put(PROP_UPERF_FAIL_SAFE, "0");
        String result = new HealthFixture().schedulerHealthStateLines();
        if (!result.contains("\\nschedulerHealth=" + health + "\\n") || !result.contains("\\nthreadManagerState=" + state + "\\n")) throw new AssertionError(result);
    }
    public static void main(String[] args) {
        check("1","0","running","running","ok","zuiopt_active");
        check("1","1","stopped","running","ok","android_default_failsafe");
        check("1","0","stopped","running","zuiopt_not_running_while_active","inactive_or_unhealthy");
        check("1","1","running","running","zuiopt_not_stopped_in_failsafe","inactive_or_unhealthy");
        check("1","unknown","stopped","running","invalid_zuiopt_failure","inactive_or_unhealthy");
        check("1","1","stopped","stopped","uperf_stopped_while_active","android_default_failsafe");
        check("0","0","running","stopped","zui_scheduler_running_while_inactive","zuiopt_active");
        System.out.println("TERMINAL_SCHEDULER_HEALTH=PASS cases=7");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='zuiopt-health-') as directory:
    path = Path(directory)/'HealthFixture.java'
    path.write_text(fixture, encoding='utf8')
    subprocess.run(['javac','-encoding','UTF-8',str(path)],check=True)
    subprocess.run(['java','-cp',directory,'HealthFixture'],check=True)
