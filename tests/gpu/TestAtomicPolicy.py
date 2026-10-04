from pathlib import Path
import subprocess,tempfile
R=Path(__file__).resolve().parents[2];S=R/'framework_patch/src/services/com/zui/server/control'
with tempfile.TemporaryDirectory(prefix='atomic-policy-') as t:
 subprocess.run(['javac','-encoding','UTF-8','-d',t,*[str(S/n) for n in ['PolicyJson.java','GpuRange.java','AppPolicyStore.java','SettingsBackup.java','UperfConfigStore.java']],*[str(R/'tests/gpu'/n) for n in ['AppPolicyFixture.java','AtomicPolicyTest.java','FactoryResetTest.java','GpuDefaultsBatchTest.java']]],check=True)
 subprocess.run(['java','-cp',t,'com.zui.server.control.AtomicPolicyTest'],check=True)
 subprocess.run(['java','-cp',t,'com.zui.server.control.GpuDefaultsBatchTest'],check=True)
