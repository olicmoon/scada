import os, traceback

import argparse
import sqlite3

CURRENT_DIR = os.path.dirname(os.path.realpath(__file__))
IGNITION_DATA_PATH = f'{CURRENT_DIR}/gateway_data'
IGNITION_CONFIG_PATH = f'{CURRENT_DIR}/configs'
IGNITION_DATABASE_PATH = f'{IGNITION_DATA_PATH}/db/config.idb'

def backup_table(db_config_path: str, tbl_name: str):
    db_conn = None
    print(f'Backup {tbl_name}')
    try:
        # okay to copy entire table to memory but care if we're doing this for larger table than 
        # Ignition configs..
        db_conn = sqlite3.connect(':memory:')
        cur = db_conn.cursor()
        cur.execute(f'attach database "{IGNITION_DATABASE_PATH}" as attached_db')
        cur.execute(f'select sql from attached_db.sqlite_master where type="table" and name="{tbl_name}"')
        sql_create_tbl = cur.fetchone()[0]
        cur.execute(sql_create_tbl)
        cur.execute(f'insert into {tbl_name} select * from attached_db.{tbl_name}')
        db_conn.commit()
        cur.execute('detach database attached_db')

        if not os.path.exists(db_config_path):
            os.makedirs(db_config_path, exist_ok=True)

        with open(f'{db_config_path}/{tbl_name}.sql', 'w') as fp:
            for line in db_conn.iterdump():
                fp.write(f'{line}\n')
            fp.flush()
        return True
    
    except Exception as e:
        print(f'Failed to dump table : {IGNITION_DATABASE_PATH} {e}')
        print(traceback.print_exc())
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()

device_to_settings = {
        'SimulatorDevice': 'BOWERYSCADADEVICESETTINGS',
        'CompactLogix': 'COMPACTLOGIXDRIVERSETTINGS',  # Allen-Bradley CompactLogix (Legacy)
        'ControlLogix': 'CONTROLLOGIXDRIVERSETTINGS', # Allen-Bradley ControlLogix (Legacy)
        'LogixDriver': 'LOGIXDRIVERSETTINGS', # Allen-Bradley Logix Driver
        'MicroLogix': 'MICROLOGIXDRIVERSETTINGS', # Allen-Bradley MicroLogix
        'PLC5': 'PLC5DRIVERSETTINGS', # Allen-Bradley PLC5
        'SLC': 'SLCDRIVERSETTINGS', # Allen-Bradley SLC
        # BACnet/IP
        'Dnp3Driver': 'DNP3DRIVERSETTINGS', # ModbusRtuOverTcp
        'ModbusRtuOverTcp': 'MODBUSTCPDRIVERSETTINGS', # Modbus RTU over TCP
        'ModbusTcp': 'MODBUSTCPDRIVERSETTINGS', # Modbus TCP
        'com.inductiveautomation.FinsTcpDeviceType': 'FINSTCPDEVICESETTINGS', # Omron FINS/TCP
        'com.inductiveautomation.FinsUdpDeviceType': 'FINSUDPDEVICESETTINGS', # Omron FINS/UDP
        'com.inductiveautomation.omron.NjDriver': 'NJDRIVERSETTINGS', # Omron NJ Driver
        'S71200': 'S71200DRIVERSETTINGS',  # Siemens S7-1200
        'S71500': 'S71500DRIVERSETTINGS',  # Siemens S7-1500
        'S7300': 'S7300DRIVERSETTINGS', # Siemens S7-300
        'S7400': 'S7400DRIVERSETTINGS', # Siemens S7-300
        'TCPDriver': 'TCPDRIVERSETTINGS', # TCP Driver
        'UDPDriver': 'UDPDRIVERSETTINGS', # UDP Driver
        }

def backup_device_configs():
    if not os.path.exists(IGNITION_CONFIG_PATH):
        print(f'Ignition configuration path not available: {IGNITION_CONFIG_PATH}')
        return False

    db_conn = None
    db_config_path = f'{IGNITION_CONFIG_PATH}/db/devices'

    try:
        # okay to copy entire table to memory but care if we're doing this for larger table than 
        # Ignition configs..
        db_conn = sqlite3.connect(IGNITION_DATABASE_PATH)
        cur = db_conn.cursor()
        cur.execute(f'select type from DEVICESETTINGS')
        rows = cur.fetchall()

        driver_settings = set()
        for row in rows:
            driver_type = row[0]
            if driver_type not in device_to_settings:
                print(f'unknown driver: {driver_type}')
                raise ValueError(f'unknown driver {driver_type}')

            driver_settings.add(device_to_settings[driver_type])

        backup_table(db_config_path, 'DEVICESETTINGS')
        for tbl in driver_settings:
            backup_table(db_config_path, tbl)

    except Exception as e:
        print(f'Failed to dump table : {IGNITION_DATABASE_PATH} {e}')
        print(traceback.print_exc())
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()

def restore_tables(db_config_path: str):
    db_conn = None
    try:
        # okay to copy entire table to memory but care if we're doing this for larger table than 
        # Ignition configs..
        if not os.path.exists(db_config_path):
            print(f'Database configuration path not found: {db_config_path}')
            raise ValueError(f'Database configuration path not found: {db_config_path}')

        db_conn = sqlite3.connect(IGNITION_DATABASE_PATH)
        for _, _, files in os.walk(db_config_path):
            for file in files:
                if not file.endswith('.sql'):
                    continue
                tbl_name = os.path.splitext(file)[0]

                with open(f'{db_config_path}/{file}', 'r') as fp:
                    cur = db_conn.cursor()
                    cur.execute(f'drop table {tbl_name}')
                    cur.executescript(''.join(fp.readlines()))
                    db_conn.commit()
    
    except Exception as e:
        print(f'Failed to dump table : {IGNITION_DATABASE_PATH} {e}')
        print(traceback.print_exc())
        return False
    finally:
        if db_conn:
            db_conn.commit()
            db_conn.close()


def restore_device_configs():
    restore_tables(db_config_path=f'{IGNITION_CONFIG_PATH}/db/devices')


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('-b', '--backup', nargs=1, type=str, choices=('device', 'datasource'), help='backup Ignition configs')
    args = parser.parse_args()

    print(vars(args))
    if 'device' in args.backup:
        print('Start backup device configuration..')
        backup_device_configs()

    if 'datasource' in args.backup:
        print('Start backup datasource configuration..')
        print('Backup datasource configs')

