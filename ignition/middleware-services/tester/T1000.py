import api
from api import Simulator, Database, OsScadaClient
import test

@test.Case('Scan bin label and validate destination')
class T1000(test.Test):
    def __init__(self):
        self.db = Database('localhost', 5432, 'ignition_dev', 'postgres')
        self.sim = Simulator('localhost', 9888)
        self.os = OsScadaClient()
        self.bin_entries = [('bin_5001', 'cold_pack'),
                ('bin_5002', 'basil'),
                ('bin_5003', 'cold_pack'),
                ('bin_5004', 'basil'),
                ('bin_5005', 'cold_pack')]

        self.dt_list = list()

    def setup(self):
        self.db.clear()

    def teardown(self):
        pass

    @test.Step(1)
    def add_routing_instructions(self):
        for label, dest in self.bin_entries:
            print(f'Insert routing instruction {label} dessired_destination: {dest}')
            assert self.os.add_bin_routing_instruction(label, dest) == True, 'Failed to add routing instructions'

    @test.Step(2)
    def scan_bin_label_and_validate(self):
        for label, _ in self.bin_entries:
            self.sim.clear_bin_routing()

            t0 = api.get_curr_time_ms()
            self.sim.scan_bin_label(farmid=3, label=label, side='A', weight=333)
            log = self.db.get_routing_log(label=label, retry=15)
            assert log is not None, 'Routing log not found'
            assert log.destination == 'continue', f'Expected to continue but got {log.destination} for {label}'

            dt = api.get_curr_time_ms() - t0
            self.dt_list.append(dt)

            print(f'Bin {label} routed to \"{log.destination}\" ({log.reason}) took {dt}ms')

    @test.Step(3)
    def handle_invalid_label(self):
        label = 'bin_invalid'
        print(f'Scan {label}')
        self.sim.scan_bin_label(farmid=3, label=label, side='A', weight=333)
        log = self.db.get_routing_log(label=label, retry=15)
        assert log is not None, 'Routing log not found'
        assert log.destination == 'kickout', f'Expected to kickout but got {log.destination} for {label}'
        print(f'Bin {label} routed to \"{log.destination}\" ({log.reason})')

    @test.Step(4)
    def latency_check(self):
        avg = sum(self.dt_list) / len(self.dt_list)
        print(f'Average time for bin routing: {avg} from total {len(self.dt_list)} entries')


if __name__ == '__main__':
    test.execute([T1000])
