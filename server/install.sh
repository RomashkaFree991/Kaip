#!/usr/bin/env bash
# Установка Kaip на чистый Linux-сервер (Ubuntu/Debian). Запуск: sudo bash install.sh
set -euo pipefail
cd "$(dirname "$0")"

say() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

[ "$(id -u)" = 0 ] || { echo "Запусти от root: sudo bash install.sh"; exit 1; }

say "Проверяю Docker"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
if ! docker compose version >/dev/null 2>&1; then
  apt-get update && apt-get install -y docker-compose-plugin
fi
systemctl enable --now docker >/dev/null 2>&1 || true

say "Проверяю память"
mem_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
echo "Оперативной памяти: ${mem_mb} МБ"
if [ "$mem_mb" -lt 3500 ] && [ -z "$(swapon --show --noheadings)" ]; then
  free_gb=$(df -BG --output=avail / | tail -1 | tr -dc '0-9')
  swap_gb=2; [ "$free_gb" -gt 20 ] && swap_gb=4
  echo "Памяти мало — создаю файл подкачки ${swap_gb} ГБ"
  fallocate -l ${swap_gb}G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=$((swap_gb*1024))
  chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  sysctl -w vm.swappiness=60 >/dev/null
fi

if [ ! -f .env ]; then
  say "Настройка"
  ip=$(curl -4 -fsS --max-time 10 https://api.ipify.org || curl -4 -fsS --max-time 10 https://ifconfig.me || true)
  default_domain=""
  [ -n "$ip" ] && default_domain="${ip//./-}.sslip.io"
  read -rp "Домен для панели [${default_domain}]: " domain
  domain=${domain:-$default_domain}
  [ -n "$domain" ] || { echo "Не удалось узнать IP — укажи домен вручную."; exit 1; }
  while true; do
    read -rsp "Придумай пароль для входа (от 8 символов): " pass; echo
    if [ ${#pass} -lt 8 ]; then echo "Слишком короткий."; continue; fi
    if [[ "$pass" == *"'"* ]]; then echo "Без одинарных кавычек, пожалуйста."; continue; fi
    read -rsp "Повтори пароль: " pass2; echo
    [ "$pass" = "$pass2" ] && break
    echo "Пароли не совпали."
  done
  secret=$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 48)
  cat > .env <<ENV
KAIP_DOMAIN='${domain}'
KAIP_PASSWORD='${pass}'
KAIP_SECRET='${secret}'
KAIP_HTTPS='1'
KAIP_GEOMETRY='1280x720'
ENV
  chmod 600 .env
fi

set_env() {
  if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1='$2'|" .env; else echo "$1='$2'" >> .env; fi
}
port_busy() {
  if command -v ss >/dev/null 2>&1; then
    [ -n "$(ss -Hltn "sport = :$1" 2>/dev/null)" ]
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
  fi
}
port_owner() {
  local owner=""
  command -v ss >/dev/null 2>&1 && owner=$(ss -Hltnp "sport = :$1" 2>/dev/null | grep -o 'users:(("[^"]*"' | head -1 | cut -d'"' -f2)
  echo "${owner:-неизвестно}"
}

say "Проверяю порты"
docker compose rm -sf caddy >/dev/null 2>&1 || true   # наш прошлый запуск не считается
CANDIDATES="8443 2083 2087 2096 9443 10443 18443 20443 28443 30443"
free_ports() {   # free_ports N [исключить...] — первые N свободных портов из списка
  local n=$1 found=() p; shift
  for p in $CANDIDATES; do
    [[ " $* " == *" $p "* ]] && continue
    port_busy "$p" || found+=("$p")
    [ ${#found[@]} -ge "$n" ] && break
  done
  echo "${found[@]}"
}

tls_mode=acme
https_port=443
if port_busy 443; then
  echo "Порт 443 занят программой: $(port_owner 443)."
  echo "Без портов 80/443 бесплатный сертификат Let's Encrypt не выдают —"
  echo "включаю собственный сертификат на свободном порту."
  tls_mode=self
  https_port=$(free_ports 1)
  [ -n "$https_port" ] || { echo "Не нашёл свободного порта из списка: $CANDIDATES"; exit 1; }
fi
http_port=80
if port_busy 80; then
  echo "Порт 80 занят программой: $(port_owner 80) — обойдусь без него."
  http_port=8080
  while port_busy "$http_port" || [ "$http_port" = "$https_port" ]; do http_port=$((http_port + 1)); done
fi
spare=$(free_ports 3 "$https_port" "$http_port")
echo "HTTPS-порт: $https_port   HTTP-порт: $http_port   запасные свободные: ${spare:-нет}"
set_env KAIP_TLS_MODE "$tls_mode"
set_env KAIP_HTTP_PORT "$http_port"
set_env KAIP_HTTPS_PORT "$https_port"

domain=$(grep '^KAIP_DOMAIN=' .env | cut -d"'" -f2)
if [ "$tls_mode" = self ]; then
  mkdir -p caddy/certs
  if [ ! -f caddy/certs/kaip.crt ] || ! grep -qx "$domain" caddy/certs/domain 2>/dev/null; then
    say "Создаю собственный сертификат для $domain"
    command -v openssl >/dev/null 2>&1 || apt-get install -y openssl
    san="DNS:${domain}"
    ip_from_domain=$(echo "$domain" | grep -oE '^[0-9]+-[0-9]+-[0-9]+-[0-9]+' | tr '-' '.' || true)
    [ -n "$ip_from_domain" ] && san="${san},IP:${ip_from_domain}"
    openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=${domain}" \
      -addext "subjectAltName=${san}" \
      -keyout caddy/certs/kaip.key -out caddy/certs/kaip.crt 2>/dev/null
    chmod 644 caddy/certs/kaip.crt && chmod 600 caddy/certs/kaip.key
    echo "$domain" > caddy/certs/domain
  fi
  fingerprint=$(openssl x509 -in caddy/certs/kaip.crt -noout -fingerprint -sha256 | cut -d= -f2)
fi

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  say "Открываю порты в ufw"
  ufw allow "$http_port"/tcp && ufw allow "$https_port"/tcp
fi

mkdir -p data/home data/kaip
say "Собираю и запускаю (первый раз 5–15 минут)"
docker compose up -d --build

say "Готово!"
url="https://${domain}"
[ "$https_port" != 443 ] && url="${url}:${https_port}"
echo "Открой на телефоне:  ${url}"
if [ "$tls_mode" = self ]; then
  echo
  echo "Сертификат собственный (порт 443 занят), поэтому:"
  echo "  • приложение Kaip один раз спросит, доверять ли серверу — сверь отпечаток:"
  echo "      ${fingerprint}"
  echo "  • в Chrome будет предупреждение: «Дополнительные» → «Перейти на сайт»."
  echo "Если провайдер закрыл порт ${https_port}, запасные свободные порты: ${spare:-нет}"
  echo "(поставь другой в KAIP_HTTPS_PORT в файле .env и выполни: docker compose up -d)"
else
  echo "Сертификат HTTPS выпускается автоматически в первую минуту — если не открылось, подожди и обнови."
fi
