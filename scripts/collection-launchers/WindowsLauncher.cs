using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

internal static class WindowsLauncher
{
    private const string Magic = "WFQCOLL1";

    [STAThread]
    private static int Main(string[] args)
    {
        bool verify = args.Length == 1 && args[0] == "--verify-package";
        try
        {
            Dictionary<string, object> payload = ReadPayload(Assembly.GetExecutingAssembly().Location);
            string script = Required(payload, "script", 262144);
            string configuration = Required(payload, "configuration", 8192);
            if (verify) return 0;

            string key = Hex(SHA256.Create().ComputeHash(Encoding.UTF8.GetBytes(configuration)));
            using (Mutex mutex = new Mutex(false, @"Local\WFQAssetCollector-" + key))
            {
                bool held;
                try { held = mutex.WaitOne(0); }
                catch (AbandonedMutexException) { held = true; }
                if (!held)
                {
                    MessageBox.Show("采集窗口已经打开，请完成当前采集。", "电脑资产采集", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return 0;
                }
                try
                {
                    string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WFQAssetCollector");
                    EnsureDirectory(root);
                    string directory = Path.Combine(root, key);
                    EnsureDirectory(directory);
                    Write(Path.Combine(directory, "Collect.ps1"), script, new UTF8Encoding(true));
                    Write(Path.Combine(directory, "config.json"), configuration, new UTF8Encoding(false));
                    string powershell = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), @"WindowsPowerShell\v1.0\powershell.exe");
                    var start = new ProcessStartInfo(powershell, "-NoProfile -STA -WindowStyle Hidden -ExecutionPolicy Bypass -File \"" + Path.Combine(directory, "Collect.ps1") + "\"");
                    start.UseShellExecute = false;
                    start.CreateNoWindow = true;
                    start.WorkingDirectory = directory;
                    using (Process child = Process.Start(start))
                    {
                        child.WaitForExit();
                        return child.ExitCode;
                    }
                }
                finally { mutex.ReleaseMutex(); }
            }
        }
        catch
        {
            if (!verify) MessageBox.Show("采集程序无法启动。请从资产系统重新下载完整 EXE，并检查当前用户目录和 Windows PowerShell 是否可用。", "电脑资产采集", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private static Dictionary<string, object> ReadPayload(string file)
    {
        using (FileStream stream = File.OpenRead(file))
        using (BinaryReader reader = new BinaryReader(stream))
        {
            if (stream.Length < 44) throw new InvalidDataException();
            stream.Position = stream.Length - 12;
            int length = reader.ReadInt32();
            if (Encoding.ASCII.GetString(reader.ReadBytes(8)) != Magic || length < 1 || length > 1048576 || length > stream.Length - 44)
                throw new InvalidDataException();
            stream.Position = stream.Length - 44 - length;
            byte[] data = reader.ReadBytes(length);
            byte[] expected = reader.ReadBytes(32);
            byte[] actual = SHA256.Create().ComputeHash(data);
            for (int i = 0; i < 32; i++) if (expected[i] != actual[i]) throw new InvalidDataException();
            return new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(new UTF8Encoding(false, true).GetString(data));
        }
    }
    private static string Required(Dictionary<string, object> payload, string name, int maximum)
    {
        object raw;
        if (payload == null || !payload.TryGetValue(name, out raw) || !(raw is string)) throw new InvalidDataException();
        string value = (string)raw;
        if (String.IsNullOrWhiteSpace(value) || value.Length > maximum) throw new InvalidDataException();
        return value;
    }
    private static void EnsureDirectory(string directory)
    {
        Directory.CreateDirectory(directory);
        if ((File.GetAttributes(directory) & FileAttributes.ReparsePoint) != 0) throw new IOException();
    }
    private static void Write(string file, string text, Encoding encoding)
    {
        if (File.Exists(file) && (File.GetAttributes(file) & FileAttributes.ReparsePoint) != 0) throw new IOException();
        File.WriteAllText(file, text, encoding);
    }
    private static string Hex(byte[] value) { return BitConverter.ToString(value).Replace("-", "").ToLowerInvariant(); }
}
