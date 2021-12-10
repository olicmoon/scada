import os, re, enum
import json

import sqlite3
import shutil
import xmltodict

from common import logger, restart_ignition
from common import IGNITION_INSTALL_LOCATION, PROVISION_CACHE_PATH, IGNITION_UID, IGNITION_GID

class ProvisionStatus(enum.Enum):
    UNKNOWN = 'UNKNOWN'
    PROVISIONED = 'PROVISIONED'

class DeploymentType(enum.Enum):
    DEV = 'DEV'
    STAGING = 'STAGING'
    PROD = 'PROD'

def store_provision_cache(deployment: DeploymentType, status: ProvisionStatus):
    cache = dict()
    cache['deployment'] = deployment.value
    cache['status'] = status.value

    cache_file = f'{IGNITION_INSTALL_LOCATION}/{PROVISION_CACHE_PATH}'
    if os.path.exists(cache_file):
        os.remove(cache_file)

    with open(cache_file, 'w') as fp:
        json.dump(cache, fp)
        fp.flush()

def read_provisioning_status() -> ProvisionStatus:
    cache_file = f'{IGNITION_INSTALL_LOCATION}/{PROVISION_CACHE_PATH}'
    if os.path.exists(cache_file):
        with open(cache_file, 'r') as fp:
            cache = json.load(fp)
            if 'status' in cache:
                return ProvisionStatus(cache['status'])

    return ProvisionStatus.UNKNOWN

def register_modules(db_path: str) -> bool:
    db_conn = None
    try:
        db_conn = sqlite3.connect(db_path)

        module_dir = '/modules'
        for _, _, files in os.walk(module_dir):
            for fname in files:
                if not fname.endswith('.modl'):
                    continue

                module_path = f'{module_dir}/{fname}'
                module_install_path = f'{IGNITION_INSTALL_LOCATION}/user-lib/modules/{fname}'
                shutil.copy(module_path, module_install_path)
                os.chown(module_install_path, IGNITION_UID, IGNITION_GID)
                logger.info(f'Installing {module_path}')

                module_info = xmltodict.parse(os.popen(f'unzip -qq -c "{module_path}" module.xml').read())
                module_id = module_info['modules']['module']['id']

                license_crc32 = os.popen(f'unzip -qq -c "{module_path}" license.html \
                        | gzip -c | tail -c8 | od -t u4 -N 4 -A n | cut -c 2-').read().strip()

                keytool_path = os.popen('which keytool').read().strip()
                cert_info = os.popen(f'unzip -qq -c {module_path} certificates.p7b | \
                        {keytool_path} -printcert -v | head -n 9').read().strip()

                subject_name = ''
                thumbprint = ''
                fingerprints_line = 0
                linenum = 0
                for line in cert_info.split('\n'):
                    linenum = linenum + 1
                    # print(f'[{linenum}] {line}')
                    if 'Owner' in line:
                        m = re.search('CN=(.+?),', line)
                        subject_name = m.group(1)

                    if 'Certificate fingerprints' in line:
                        fingerprints_line = linenum

                    if fingerprints_line > 0 and 'SHA1:' in line:
                        arr = line.split('SHA1: ')
                        if len(arr) == 2:
                            thumbprint = arr[1].replace(':', '').lower()

                logger.info(f'       Module: {module_id} {fname}')
                logger.info(f'   Thumbprint: {thumbprint}')
                logger.info(f' Subject Name: {subject_name}')
                logger.info(f'License crc32: {license_crc32}')

                cur = db_conn.cursor()

                cur.execute(f'SELECT 1 FROM CERTIFICATES WHERE lower(hex(THUMBPRINT)) = "{thumbprint}"')
                rows = cur.fetchall()
                thumbprint_already_exists = len(rows) != 0

                if not thumbprint_already_exists:
                    cur.execute('SELECT COALESCE(MAX(CERTIFICATES_ID)+1,1) FROM CERTIFICATES')
                    rows = cur.fetchall()
                    cert_id = rows[0][0]
                    logger.info(f'Insert thumbprint {cert_id}')
                    cur.executescript(f'INSERT INTO CERTIFICATES (CERTIFICATES_ID, THUMBPRINT, SUBJECTNAME) \
                            VALUES ({cert_id}, x\'{thumbprint}\', "{subject_name}"); \
                            UPDATE SEQUENCES SET val={cert_id} WHERE name="CERTIFICATES_SEQ"')

                cur.execute(f'SELECT 1 FROM EULAS WHERE MODULEID="{module_id}" AND CRC="{license_crc32}"')
                rows = cur.fetchall()
                module_id_already_exists = len(rows) != 0
                if not module_id_already_exists:
                    cur.execute('SELECT COALESCE(MAX(EULAS_ID)+1,1) FROM EULAS')
                    rows = cur.fetchall()
                    eula_id = rows[0][0]

                    logger.info(f'Accepting EULA id:{module_id} {eula_id}')
                    cur.executescript(f'INSERT INTO EULAS (EULAS_ID, MODULEID, CRC) \
                            VALUES ({eula_id}, "{module_id}", "{license_crc32}"); \
                            UPDATE SEQUENCES SET val={eula_id} WHERE name="EULAS_SEQ"')

                cur.close()

    except Exception as e:
        logger.error(f'Failed to install modules {db_path}: {e}')
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()

    return True

class DeviceType(enum.Enum):
    OPC = 'OPC'
    SIMULATOR = 'SimulatorDevice'

class DeviceConnection:
    def __init__(self, name: str, dtype: DeviceType, desc: str, enabled: bool, settings_tbl: str, settings: dict):
        self.name = name
        self.dtype = dtype
        self.desc = desc
        self.enabled = enabled
        self.settings_tbl = settings_tbl
        self.settings = settings

    @staticmethod
    def from_json(json_data: str):
        data = json.loads(json_data)
        return DeviceConnection(data['name'], data['type'], data['desc'], data['enabled'], data['settings_tbl'], data['settings'])

    def to_json(self):
        return {
                'name': self.name,
                'type': self.dtype,
                'desc': self.desc,
                'enabled': self.enabled,
                'settings_tbl': self.settings_tbl,
                'settings': self.settings
                }

simulator_device = DeviceConnection(name='Bowery SCADA Simulator',
        dtype= DeviceType.SIMULATOR, desc='Provides simulated test environment', enabled=True,
        settings_tbl='BOWERYSCADADEVICESETTINGS',
        settings = [
            { 'name': 'FARMCODE', 'type': 'varchar(4096)', 'not_null': True, 'value': 'F2' }
            ])

def register_devices(db_path: str) -> bool:
    db_conn = None
    tbl = 'DEVICESETTINGS'
    try:
        db_conn = sqlite3.connect(db_path)
        cur = db_conn.cursor()

        # check if simulator device exists
        cur.execute(f'SELECT 1 FROM {tbl} WHERE NAME = "{simulator_device.name}"')
        rows = cur.fetchall()
        if len(rows) == 0:
            logger.info(f'Inserting {simulator_device.name}')
            cur.execute(f'SELECT COALESCE(MAX(DEVICESETTINGS_ID)+1, 11) FROM {tbl}')
            rows = cur.fetchall()
            next_id = rows[0][0]
            if next_id <= 11:
                next_id = 11
            cur.execute(f'INSERT INTO {tbl} (DEVICESETTINGS_ID, NAME, TYPE, DESCRIPTION, ENABLED) VALUES \
                    ({next_id}, "{simulator_device.name}", "{simulator_device.dtype}", "{simulator_device.desc}", \
                    {simulator_device.enabled})')

        cur.execute(f'SELECT DEVICESETTINGS_ID FROM {tbl} WHERE NAME = "{simulator_device.name}"')
        rows = cur.fetchall()
        dev_id = rows[0][0]

        cur.execute('SELECT EXISTS (SELECT * FROM sqlite_master WHERE type="table" and name="{simulator_device.settings_tbl}")')
        rows = cur.fetchall()
        if rows[0][0] == 0:
            logger.info(f'Creating setting table {simulator_device.settings_tbl} {simulator_device.name}')
            create_query = f'CREATE TABLE {simulator_device.settings_tbl} (DEVICESETTINGSID NUMERIC(18,0) NOT NULL, '
            for setting in simulator_device.settings:
                create_query += f'{setting["name"]} {setting["type"]}'
                if setting['not_null']:
                    create_query += ' NOT NULL, '
                else:
                    create_query += ', '
            create_query += f'PRIMARY KEY (DEVICESETTINGSID))'
            cur.execute(create_query)

            insert_query = f'INSERT INTO {simulator_device.settings_tbl} (DEVICESETTINGSID'
            for setting in simulator_device.settings:
                insert_query += f', {setting["name"]}'
            insert_query += f') VALUES ({dev_id}'
            for setting in simulator_device.settings:
                insert_query += f', "{setting["value"]}"'
            insert_query += ')'
            cur.execute(insert_query)

    except Exception as e:
        logger.error(f'Failed to install modules {db_path}: {e}')
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()

    return True

def register_tagprovidiers(db_path: str) -> bool:
    db_conn = None
    try:
        db_conn = sqlite3.connect(db_path)

    except Exception as e:
        logger.error(f'Failed to install modules {db_path}: {e}')
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()

    return True

def register_datasources(db_path: str) -> bool:
    db_conn = None
    try:
        db_conn = sqlite3.connect(db_path)

    except Exception as e:
        logger.error(f'Failed to install modules {db_path}: {e}')
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()

    return True

def perform_provisioning(deployment: DeploymentType) -> bool:
    db_path = f'{IGNITION_INSTALL_LOCATION}/data/db/config.idb'

    status = read_provisioning_status()
    if status != ProvisionStatus.PROVISIONED:
        logger.info('Start provisioning')
        if not register_modules(db_path):
            return False

        # if not register_jdbc_driver(db_path):
        #    return False

        if not register_devices(db_path):
            return False

        # Restart ignition gateway to apply changes
        store_provision_cache(deployment, ProvisionStatus.PROVISIONED)
        logger.info('Finish provisioning.. restarting gateway..')
        restart_ignition()
        return True
    else:
        # Install latest module in /modules
        if not register_modules(db_path):
            return False

        return True

