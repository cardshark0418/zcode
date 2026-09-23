param()
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

if (-not ('ZcodeNativeFolderPicker' -as [type])) {
  Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class ZcodeNativeFolderPicker {
  [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
  private static extern void SHCreateItemFromParsingName(
    [In][MarshalAs(UnmanagedType.LPWStr)] string pszPath,
    IntPtr pbc,
    [In] ref Guid riid,
    [Out][MarshalAs(UnmanagedType.Interface)] out IShellItem ppv);

  [DllImport("user32.dll")]
  private static extern IntPtr GetForegroundWindow();

  [DllImport("user32.dll")]
  private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

  [ComImport, Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")]
  private class FileOpenDialogRCW {}

  [ComImport, Guid("42f85136-db7e-439c-85f1-e4075d135fc8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IFileDialog {
    [PreserveSig] int Show(IntPtr parent);
    void SetFileTypes(uint cFileTypes, IntPtr rgFilterSpec);
    void SetFileTypeIndex(uint iFileType);
    void GetFileTypeIndex(out uint piFileType);
    void Advise(IntPtr pfde, out uint pdwCookie);
    void Unadvise(uint dwCookie);
    void SetOptions(uint fos);
    void GetOptions(out uint pfos);
    void SetDefaultFolder(IShellItem psi);
    void SetFolder(IShellItem psi);
    void GetFolder(out IShellItem ppsi);
    void GetCurrentSelection(out IShellItem ppsi);
    void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string pszName);
    void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string pszName);
    void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
    void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string pszText);
    void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
    void GetResult(out IShellItem ppsi);
    void AddPlace(IShellItem psi, int fdap);
    void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string pszDefaultExtension);
    void Close(int hr);
    void SetClientGuid(ref Guid guid);
    void ClearClientData();
    void SetFilter(IntPtr pFilter);
  }

  [ComImport, Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IShellItem {
    void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
    void GetParent(out IShellItem ppsi);
    void GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string ppszName);
    void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
    void Compare(IShellItem psi, uint hint, out int piOrder);
  }

  private const uint FOS_PICKFOLDERS = 0x20;
  private const uint FOS_FORCEFILESYSTEM = 0x40;
  private const uint FOS_PATHMUSTEXIST = 0x800;
  private const uint SIGDN_FILESYSPATH = 0x80058000;
  private const byte VK_MENU = 0x12;
  private const uint KEYEVENTF_KEYUP = 0x0002;

  public static string Pick(string initial) {
    keybd_event(VK_MENU, 0, 0, UIntPtr.Zero);
    keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);

    var dialog = (IFileDialog)new FileOpenDialogRCW();
    dialog.SetOptions(FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST);
    dialog.SetTitle("\u9009\u62e9\u6587\u4ef6\u5939");
    dialog.SetOkButtonLabel("\u9009\u62e9\u6587\u4ef6\u5939");
    if (!string.IsNullOrWhiteSpace(initial)) {
      try {
        Guid shellItemGuid = new Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe");
        IShellItem item;
        SHCreateItemFromParsingName(initial, IntPtr.Zero, ref shellItemGuid, out item);
        dialog.SetFolder(item);
      } catch {}
    }
    IntPtr owner = GetForegroundWindow();
    int hr = dialog.Show(owner);
    if (hr != 0) return null;
    IShellItem result;
    dialog.GetResult(out result);
    string path;
    result.GetDisplayName(SIGDN_FILESYSPATH, out path);
    return path;
  }
}
'@
}

Write-Output 'READY'
[Console]::Out.Flush()

while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  $line = $line.Trim()
  if ($line -eq 'QUIT') { break }
  if ($line -eq 'PING') {
    Write-Output 'PONG'
    [Console]::Out.Flush()
    continue
  }
  if ($line.StartsWith('PICK')) {
    $initial = ''
    $tab = $line.IndexOf([char]9)
    if ($tab -lt 0) { $tab = $line.IndexOf(' ') }
    if ($tab -ge 0 -and ($tab + 1) -lt $line.Length) {
      $initial = $line.Substring($tab + 1).Trim()
    }
    try {
      $picked = [ZcodeNativeFolderPicker]::Pick($initial)
      if ($picked) {
        Write-Output ('PICKED' + [char]9 + $picked)
      } else {
        Write-Output 'CANCELLED'
      }
    } catch {
      Write-Output ('ERROR' + [char]9 + $_.Exception.Message)
    }
    [Console]::Out.Flush()
    continue
  }
  Write-Output ('ERROR' + [char]9 + 'unknown command')
  [Console]::Out.Flush()
}
