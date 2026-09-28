#!/usr/bin/env bash
# Provisiona o modelo do Vosk no aparelho ligado por USB.
#
# O modelo (~53 MB descompactado) não vai no APK nem no git: o app só liga o motor
# offline quando encontra o diretório completo em filesDir. Sem ele, a fala segue no
# motor do sistema, como sempre foi.
#
#   bash scripts/vosk-model.sh            # baixa (se preciso) e envia para o aparelho
#   bash scripts/vosk-model.sh --baixar   # só baixa, sem aparelho
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODELO="vosk-model-small-pt-0.3"
URL="https://alphacephei.com/vosk/models/${MODELO}.zip"
CACHE="${VOSK_CACHE:-$HOME/.cache/fala-agenda}"
PACOTE="${VOSK_PACOTE:-com.theopadilha.falaagenda}"
DESTINO="files/${MODELO}"

ZIP="${CACHE}/${MODELO}.zip"
PASTA="${CACHE}/${MODELO}"

baixar() {
  mkdir -p "$CACHE"
  if [ ! -f "$ZIP" ]; then
    echo "Baixando $URL"
    curl -fsSL -o "$ZIP" "$URL"
  fi
  if [ ! -d "$PASTA" ]; then
    echo "Descompactando em $CACHE"
    unzip -q -o "$ZIP" -d "$CACHE"
  fi
  # As duas peças que o Vosk precisa para abrir o modelo.
  for arquivo in final.mdl mfcc.conf; do
    [ -f "$PASTA/$arquivo" ] || { echo "Modelo incompleto: falta $arquivo"; exit 1; }
  done
  echo "Modelo pronto em $PASTA"
}

enviar() {
  command -v adb >/dev/null 2>&1 || { echo "adb não encontrado no PATH"; exit 1; }
  adb get-state >/dev/null 2>&1 || { echo "Nenhum aparelho (ou emulador) conectado."; exit 1; }

  echo "Enviando para $PACOTE (build debug precisa do sufixo .debug)"
  adb push "$PASTA" /data/local/tmp/ >/dev/null
  adb shell "run-as $PACOTE mkdir -p $DESTINO"
  adb shell "run-as $PACOTE cp -r /data/local/tmp/${MODELO}/. $DESTINO/"
  adb shell "rm -rf /data/local/tmp/${MODELO}"
  adb shell "run-as $PACOTE ls $DESTINO" | head -5
  echo "Pronto. Reinstalar o app NÃO apaga files/ — o modelo continua no lugar."
}

baixar
[ "${1:-}" = "--baixar" ] || enviar
