import os

IGNITION_INSTALL_LOCATION = os.getenv('IGNITION_INSTALL_LOCATION', None)
IGNITION_UID = 999
IGNITION_GID = 999
IGNITION_PORT = 8088
DATA_VOLUME_PATH = '/data'
PROVISION_CACHE_PATH ='data/bowery_provisioning.cache'

IGNITION_VERSION = os.popen(f'cat "{IGNITION_INSTALL_LOCATION}/lib/install-info.txt" | grep gateway.version | cut -d = -f 2').read().strip()

if IGNITION_INSTALL_LOCATION is None:
    raise ValueError("Undefined env: IGNITION_INSTALL_LOCATION")

INIT_FILE = "/usr/local/share/ignition/data/init.properties"
XML_FILE = f'{IGNITION_INSTALL_LOCATION}/data/gateway.xml_clean'


LOG_TAG = 'init     |'
class logger:
    @staticmethod
    def info(s):
        print(f'{LOG_TAG} INFO: {s}')

    @staticmethod
    def warn(s):
        print(f'{LOG_TAG} WARN: {s}')

    @staticmethod
    def error(s):
        print(f'{LOG_TAG} ERROR: {s}')


def restart_ignition():
    gwcmd_path = f'{IGNITION_INSTALL_LOCATION}/gwcmd.sh'
    os.system(f'{gwcmd_path} -r')

