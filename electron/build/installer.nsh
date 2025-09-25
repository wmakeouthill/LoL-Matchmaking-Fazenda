!macro customInstall
  ; Criar atalhos adicionais
  CreateShortCut "$DESKTOP\${PRODUCT_NAME}.lnk" "$INSTDIR\${PRODUCT_FILENAME}" "" "$INSTDIR\${PRODUCT_FILENAME}" 0
  
  ; Registrar associações de arquivo (opcional)
  WriteRegStr HKCR ".lolmatch" "" "LoLMatchmakingFile"
  WriteRegStr HKCR "LoLMatchmakingFile" "" "LoL Matchmaking File"
  WriteRegStr HKCR "LoLMatchmakingFile\DefaultIcon" "" "$INSTDIR\${PRODUCT_FILENAME},0"
  WriteRegStr HKCR "LoLMatchmakingFile\shell\open\command" "" '"$INSTDIR\${PRODUCT_FILENAME}" "%1"'
  
  ; Verificar se o League of Legends está instalado
  ReadRegStr $0 HKLM "SOFTWARE\WOW6432Node\Riot Games, Inc\League of Legends" "Location"
  ${If} $0 != ""
    DetailPrint "League of Legends encontrado em: $0"
  ${Else}
    ReadRegStr $0 HKLM "SOFTWARE\Riot Games, Inc\League of Legends" "Location"
    ${If} $0 != ""
      DetailPrint "League of Legends encontrado em: $0"
    ${Else}
      DetailPrint "League of Legends não encontrado. Certifique-se de que está instalado."
    ${EndIf}
  ${EndIf}
!macroend

!macro customUnInstall
  ; Remover associações de arquivo
  DeleteRegKey HKCR ".lolmatch"
  DeleteRegKey HKCR "LoLMatchmakingFile"
  
  ; Remover dados do aplicativo (opcional)
  RMDir /r "$APPDATA\${PRODUCT_NAME}"
!macroend 