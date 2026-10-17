#!/bin/bash
# The load generator's first boot (EC2 user data): Docker, and room for thousands of connections from one machine.
# drive.py does the rest when run.sh starts a test.
set -euxo pipefail
dnf install -y docker
systemctl enable --now docker
cat > /etc/sysctl.d/90-placemap-load.conf <<'EOF'
net.ipv4.ip_local_port_range = 10240 65000
net.core.somaxconn = 4096
net.ipv4.tcp_tw_reuse = 1
fs.file-max = 1000000
EOF
sysctl --system
mkdir -p /opt/placemap
touch /opt/placemap/ready
