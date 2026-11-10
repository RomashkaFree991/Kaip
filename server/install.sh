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

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  say "Открываю порты 80 и 443 в ufw"
  ufw allow 80/tcp && ufw allow 443/tcp
fi

mkdir -p data/home data/kaip
say "Собираю и запускаю (первый раз 5–15 минут)"
docker compose up -d --build

domain=$(grep '^KAIP_DOMAIN=' .env | cut -d"'" -f2)
say "Готово!"
echo "Открой на телефоне:  https://${domain}"
echo "Сертификат HTTPS выпускается автоматически в первую минуту — если не открылось, подожди и обнови."
