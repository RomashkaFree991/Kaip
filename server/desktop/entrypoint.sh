#!/bin/bash
# Готовит папки (они лежат на томах и переживают пересборку контейнера) и запускает supervisord.
set -e
mkdir -p /data/shared /data/programs /data/debs /data/downloads /data/logs/apps
chown user:user /home/user /data/shared
if [ ! -f /home/user/.kaip-init ]; then
  cp -rnT /etc/skel /home/user
  cp -rnT /opt/kaip-skel /home/user
  touch /home/user/.kaip-init
  chown -R user:user /home/user
fi
mkdir -p /home/user/Applications /tmp/runtime-user
chown user:user /home/user/Applications /tmp/runtime-user
chmod 700 /tmp/runtime-user
ln -sfn /data/shared /home/user/Телефон
chown -h user:user /home/user/Телефон
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
exec /usr/bin/supervisord -c /etc/kaip-supervisord.conf
