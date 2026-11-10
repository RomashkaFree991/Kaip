# Kaip desktop: виртуальный рабочий стол Linux + Wine, доступный из браузера.
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=ru_RU.UTF-8 \
    LC_ALL=ru_RU.UTF-8 \
    KAIP_GEOMETRY=1280x720

RUN dpkg --add-architecture i386 \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      tigervnc-standalone-server tigervnc-tools novnc websockify \
      openbox tint2 xterm x11-xserver-utils x11-utils xfonts-base dbus-x11 \
      wine wine32 wine64 libwine libwine:i386 fonts-wine \
      python3 python3-flask python3-waitress python3-psutil \
      supervisor sudo locales ca-certificates curl wget procps file \
      fonts-dejavu-core fonts-liberation \
      libgtk-3-0 libnss3 libasound2 libgbm1 libxss1 libxshmfence1 libsecret-1-0 \
      libnotify4 libxtst6 libatspi2.0-0 libdrm2 xdg-utils \
 && sed -i 's/^# *\(ru_RU.UTF-8\)/\1/; s/^# *\(en_US.UTF-8\)/\1/' /etc/locale.gen \
 && locale-gen \
 && rm -rf /var/lib/apt/lists/*

# Обычный пользователь, от которого запускаются программы (Electron и Wine не любят root).
RUN useradd -m -u 1000 -s /bin/bash user \
 && echo 'user ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/user \
 && chmod 440 /etc/sudoers.d/user

COPY supervisord.conf /etc/kaip-supervisord.conf
COPY entrypoint.sh /usr/local/bin/kaip-entrypoint
COPY skel/ /opt/kaip-skel/
COPY launcher/ /opt/kaip/
RUN chmod +x /usr/local/bin/kaip-entrypoint

EXPOSE 8000 6080
ENTRYPOINT ["/usr/local/bin/kaip-entrypoint"]
