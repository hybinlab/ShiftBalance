!macro NSIS_HOOK_POSTINSTALL
  ; The full signed redistributable is bundled; installation does not download it.
  ExecWait '"$INSTDIR\payload\vc_redist.x64.exe" /install /quiet /norestart' $0
  ${If} $0 != 0
  ${AndIf} $0 != 1638
  ${AndIf} $0 != 3010
    MessageBox MB_OK|MB_ICONSTOP "安装运行组件失败（$0）。请运行安装目录下 payload\vc_redist.x64.exe 后重试。"
  ${EndIf}
!macroend
