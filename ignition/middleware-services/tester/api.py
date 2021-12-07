import time
import subprocess
import psycopg2

from typing import Optional

def get_curr_time_ms():
    return round(time.time() * 1000)

class Simulator:
    def __init__(self, host: str, port: int):
        self.ssh_cmd = f'ssh {host} -p {port}'

    def clear_bin_routing(self) -> bool:
        self.run_ssh('clear_bin_routing')

    def scan_bin_label(self, farmid: int, label: str, side: str, weight: int) -> bool:
        rc = self.run_ssh(f'scan_bin_label --farmid {farmid} --label {label} --side {side} --weight {weight}')
        return rc == 0

    def run_ssh(self, cmd: str) -> int:
        try:
            res = subprocess.run(f'{self.ssh_cmd} {cmd}',
                    shell=True,
                    capture_output=True,
                    universal_newlines=True,
                    check=True)
            if res.stdout:
                print(f'{res.stdout}')
            if res.stderr:
                print(f'err: {res.stderr}')
            if res.returncode != 0:
                print(f'ssh command {cmd} return {res.returncode}')
            return res.returncode
        except Exception as error:
            print(error)
            return -1

class BinRoutingLog:
    def __init__(self, row):
        self.row = row  # database row
        self.id = row[0]
        self.raw_bin_label = row[1]
        self.parsed_bin_label = row[2]
        self.destination = row[3]
        self.reason = row[5]
        self.weight_grams = row[6]
        self.device_datetime = row[7]

class Database:
    def __init__(self, host:str, port:int, database: str, user:str, passwd:Optional[str]=None):
        self.conn = psycopg2.connect(host = host, port = port, database = database, 
                user = "postgres", password = passwd)
        self.routing_log_tbl = 'bin_conveyance_farm_2_prototype_cold_pack_weigh_routing_logs'
        self.routing_entry_tbl = 'bin_conveyance_routing_entries'

    def get_routing_log(self, label: str, retry: int = 30) -> Optional[BinRoutingLog]:
        """ Read bin routing log associated with parsed bin label

        :param retry time seconds to retry database query
        :return BinRoutingLog representing database row or None if data was not found
        """
        cursor = self.conn.cursor()
        t = get_curr_time_ms()
        tn = t + (retry * 1000)

        while t < tn:
            query = f'select * from {self.routing_log_tbl} where parsed_bin_label = \'{label}\''
            cursor.execute(query)
            rows = cursor.fetchall()
            if len(rows) > 0:
                return BinRoutingLog(rows[0])
            t = get_curr_time_ms()
            time.sleep(0.1)

        return None

    def clear(self):
        cursor = self.conn.cursor()
        query = f'delete from {self.routing_log_tbl}'
        cursor.execute(query)
        query = f'delete from {self.routing_entry_tbl}'
        cursor.execute(query)
        self.conn.commit()

class OsScadaClient:
    """ An hacky way to invoke Elixir code """
    def __init__(self):
        self.cwd = '/Users/olic/workspace/scada/os-scada-client'

    def add_bin_routing_instruction(self, label: str, destination: str) -> bool:
        params = f'%{{bin_label: \\"{label}\\", desired_destination: \\"{destination}\\", valid_till: ~N[2022-01-01 23:00:07]}}'
        cmd = f'mix run -e \"Scada.add_bin_routing_instruction({params});\"'
        try:
            res = subprocess.run(cmd,
                    cwd=self.cwd,
                    shell=True,
                    capture_output=True,
                    universal_newlines=True)
            if res.stdout:
                print(f'{res.stdout}')
            if res.stderr:
                print(f'Err: {res.stderr}')
            if res.returncode != 0:
                print(f'shell command {cmd} return {res.returncode}')
            return res.returncode == 0
        except Exception as error:
            print(error)
            return False


if __name__ == '__main__':
    db = Database('localhost', 5432, 'ignition_dev', 'postgres')
    sim = Simulator('localhost', 9888)
    os = OsScadaClient()

    def run_test(label: str, destination: str):
        os.add_bin_routing_instruction(label, destination)
        sim.clear_bin_routing()
        sim.scan_bin_label(farmid=3, label=label, side='B', weight=321)
        log = db.get_routing_log(label)
        print(f'bin {log.parsed_bin_label} routed {log.destination}')

    db.clear()
    run_test('bin_1005', 'cold_pack')

