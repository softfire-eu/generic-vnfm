#!/bin/bash

export MONITORING_IP=
export TIMEZONE=
export BROKER_IP=
export BROKER_PORT=
export USERNAME=
export PASSWORD=
export EXCHANGE_NAME=
export EMS_HEARTBEAT=
export EMS_AUTODELETE=
export EMS_VERSION=
export ENDPOINT=

# Hostname/IP and path of the EMS repository
export UBUNTU_EMS_REPOSITORY_HOSTNAME_OR_IP="get.openbaton.org"
export UBUNTU_EMS_REPOSITORY_PATH="repos/apt/debian/"
export CENTOS_EMS_REPOSITORY_HOSTNAME_OR_IP="get.openbaton.org"
export CENTOS_EMS_REPOSITORY_PATH="repos/rpm/"

export OS_DISTRIBUTION_RELEASE_MAJOR=

export LANG=en_US.UTF-8
export LANGUAGE=en_US.UTF-8
export LC_COLLATE=C
export LC_CTYPE=en_US.UTF-8
source /etc/bashrc


################
### SoftFIRE ###
################

ipConfigUbuntu() {
	MYIP=$(ifconfig  | grep 'inet addr:'| grep -v '127.0.0.1' | cut -d: -f2 | awk '{ print $1}')
}

ipConfigCentos() {
	MYIP=$(ifconfig | grep '\binet\b'| grep -v '127.0.0.1' | cut -d: -f2 | awk '{print $2}')
}

waitForInternet() {
	for i in {1..10}; do
		if curl --fail --head --silent get.openbaton.org; then
			echo "internet connection is ok! count: $CNT"
			break
		else
			echo "waiting for Internet... loop $i sleep 15";
			sleep 15;
		fi
	done
}

detectTestbed() {
	if (echo "$MYIP" | grep -qE '192\.168\.100\.[0-9]{1,3}'); then
		echo "IP range ($MYIP) detected as FOKUS OpenSDNcore"
		echo "disabling rx and tx offloading..."
		for iface in `ls /sys/class/net/`; do ethtool -K $iface rx off tx off gro off tso off; done
	fi

	if (echo "$MYIP" | grep -qE '192\.168\.221\.[0-9]{1,3}') || (echo "$MYIP" | grep -qE '172\.16\.13\.[0-9]{1,3}') || (echo "$MYIP" | grep -qE '192\.168\.74\.[0-9]{1,3}'); then
		echo "IP range ($MYIP) detected as ericsson"
		echo "adding proxy server to git and apt"
		export http_proxy="http://10.42.137.126:8080"
		export https_proxy="http://10.42.137.126:8080"
		echo 'export http_proxy="http://10.42.137.126:8080"' >> /etc/profile
		echo 'export https_proxy="http://10.42.137.126:8080"' >> /etc/profile
		echo 'http_proxy="http://10.42.137.126:8080"' >>/etc/environment
		echo 'https_proxy="http://10.42.137.126:8080"' >>/etc/environment
		mkdir ~/.subversion
		echo '[global]' >> ~/.subversion/servers
		echo "http-proxy-host = 10.42.137.126" >> ~/.subversion/servers
		echo "http-proxy-port = 8080" >> ~/.subversion/servers
		if [ $os = "Ubuntu" ]
		then
			printf 'Acquire::http::proxy "%s";\nAcquire::https::proxy "%s";\nAcquire::ftp::proxy "%s";\n' $http_proxy $http_proxy $http_proxy >/etc/apt/apt.conf.d/00proxy
		else
			echo "proxy=$http_proxy" >> /etc/yum.conf
		fi
		git config --system http.proxy $http_proxy
		git config --system https.proxy $https_proxy
	fi

	if (echo "$MYIP" | grep -qE '10\.0\.{0,1}\.[0-9]{1,3}'); then
		echo "IP range ($MYIP) detected as Surrey"
		echo "adding DNAT rules"
		for _ip in {1..254}; do
			iptables -t nat -A OUTPUT -d 172.20.16.${_ip}/32 -j DNAT --to-destination 10.5.20.${_ip}
			iptables -t nat -A OUTPUT -d 172.20.17.${_ip}/32 -j DNAT --to-destination 10.5.21.${_ip}
			iptables -t nat -A OUTPUT -d 172.20.18.${_ip}/32 -j DNAT --to-destination 10.5.22.${_ip}
			iptables -t nat -A OUTPUT -d 172.20.19.${_ip}/32 -j DNAT --to-destination 10.5.23.${_ip}
		done
	fi
}

################
#### Ubuntu ####
################

install_ems_on_ubuntu () {
    result=$(dpkg -l | grep "ems" | grep -i "open baton\|openbaton" | wc -l)
    if [ ${result} -eq 0 ]; then
        echo "Downloading EMS from ${UBUNTU_EMS_REPOSITORY_HOSTNAME_OR_IP}"
        echo "deb http://${UBUNTU_EMS_REPOSITORY_HOSTNAME_OR_IP}/${UBUNTU_EMS_REPOSITORY_PATH} ems main" >> /etc/apt/sources.list
        wget -O - http://get.openbaton.org/public.gpg.key | apt-key add -
        apt-get update
        cp /usr/share/zoneinfo/$TIMEZONE /etc/localtime
        apt-get install -y git
        apt-get install -y --force-yes ems-$EMS_VERSION
    else
        echo "EMS is already installed"
    fi
}

install_zabbix_on_ubuntu () {
    result=$(dpkg -l | grep "zabbix-agent" | wc -l)
    if [ ${result} -eq 0 ]; then
        echo "Installing zabbix-agent for server at $MONITORING_IP"
        apt-get install -y zabbix-agent
    else
        echo "Zabbix-agent is already installed"
    fi
}


################
#### CentOS ####
################

install_ems_on_centos () {
    result=$(yum list installed | grep "ems" | grep -i "open baton\|openbaton" | wc -l)
    if [ ${result} -eq 0 ]; then
        echo "Downloading EMS from ${CENTOS_EMS_REPOSITORY_HOSTNAME_OR_IP}"
        echo "[openbaton]" >> /etc/yum.repos.d/OpenBaton.repo
        echo "name=Open Baton Repository" >> /etc/yum.repos.d/OpenBaton.repo
        echo "baseurl=http://${CENTOS_EMS_REPOSITORY_HOSTNAME_OR_IP}/${CENTOS_EMS_REPOSITORY_PATH}" >> /etc/yum.repos.d/OpenBaton.repo
        echo "gpgcheck=0" >> /etc/yum.repos.d/OpenBaton.repo
        echo "enabled=1" >> /etc/yum.repos.d/OpenBaton.repo
        cp /usr/share/zoneinfo/$TIMEZONE /etc/localtime
        yum install -y git
        yum install -y ems
        systemctl enable ems
        #systemctl start ems
    else
        echo "EMS is already installed"
    fi
}

install_zabbix_on_centos () {
    result=$( yum list installed | grep zabbix-agent | wc -l )
    if [ ${result} -eq 0 ]; then
        echo "Adding repository .."
        rpm -Uvh http://repo.zabbix.com/zabbix/3.0/rhel/${OS_DISTRIBUTION_RELEASE_MAJOR}/x86_64/zabbix-release-3.0-1.el${OS_DISTRIBUTION_RELEASE_MAJOR}.noarch.rpm
        echo "Installing zabbix-agent .."
        yum install -y zabbix zabbix-agent
    else
        echo "Zabbix-agent is already installed"
    fi
}


#############
#### EMS ####
#############

configure_ems () {
    mkdir -p /etc/openbaton/ems
    echo [ems] > /etc/openbaton/ems/conf.ini
    echo broker_ip=$BROKER_IP >> /etc/openbaton/ems/conf.ini
    echo broker_port=$BROKER_PORT >> /etc/openbaton/ems/conf.ini
    echo username=$USERNAME >> /etc/openbaton/ems/conf.ini
    echo password=$PASSWORD >> /etc/openbaton/ems/conf.ini
    echo exchange=$EXCHANGE_NAME >> /etc/openbaton/ems/conf.ini
    echo heartbeat=$EMS_HEARTBEAT >> /etc/openbaton/ems/conf.ini
    echo autodelete=$EMS_AUTODELETE >> /etc/openbaton/ems/conf.ini
    export hn=`hostname`
    echo type=$ENDPOINT >> /etc/openbaton/ems/conf.ini
    echo hostname=$hn >> /etc/openbaton/ems/conf.ini

    service ems restart
}


################
#### Zabbix ####
################

configure_zabbix () {
    sed -i -e "s|ServerActive=127.0.0.1|ServerActive=${MONITORING_IP}:10051|g" -e "s|Server=127.0.0.1|Server=${MONITORING_IP}|g" -e "s|Hostname=Zabbix server|#Hostname=|g" /etc/zabbix/zabbix_agentd.conf
    service zabbix-agent restart
}


##############
#### Main ####
##############

if [ $(cat /etc/os-release | grep -i "ubuntu" | wc -l) -gt 0 ]; then
    os=ubuntu
elif [ $(cat /etc/os-release | grep -i "centos" | wc -l) -gt 0 ]; then
    os=centos
else
    os=undefined
fi

case ${os} in
    ubuntu) 
	    ipConfigUbuntu
	    detectTestbed
	    waitForInternet

	    install_ems_on_ubuntu
        if [ -z "${MONITORING_IP}" ]; then
            echo "No MONITORING_IP is defined, I will not download zabbix-agent"
        else
	        install_zabbix_on_ubuntu
        fi
	    ;;
    centos)
	    ipConfigCentos
            detectTestbed
            waitForInternet

	    install_ems_on_centos
        if [ -z "${MONITORING_IP}" ]; then
            echo "No MONITORING_IP is defined, I will not download zabbix-agent"
        else
            yum install -y */lsb-release
            OS_DISTRIBUTION_RELEASE_MAJOR=$( lsb_release -a | grep "Release:" | awk -F'\t' '{ print $2 }' | awk -F'.' '{ print $1 }' )
            install_zabbix_on_centos
        fi
	    ;;
    *)
	    echo "OS not recognized"
	    exit 1
	    ;;
esac	

configure_ems
if [ -n "${MONITORING_IP}" ]; then
    configure_zabbix
fi
