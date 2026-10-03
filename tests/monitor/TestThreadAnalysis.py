from pathlib import Path
import subprocess,tempfile
R=Path(__file__).resolve().parents[2];S=R/'framework_patch/src/services/com/zui/server/control'
with tempfile.TemporaryDirectory(prefix='thread-analysis-') as t:
 subprocess.run(['javac','-encoding','UTF-8','-d',t,*[str(S/n) for n in ['PolicyJson.java','MonitorSnapshot.java','ThreadAnalysis.java']],str(R/'tests/monitor/ThreadAnalysisTest.java')],check=True)
 subprocess.run(['java','-cp',t,'com.zui.server.control.ThreadAnalysisTest'],check=True)
