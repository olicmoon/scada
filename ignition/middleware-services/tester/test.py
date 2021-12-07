# This source code file comprises Sonatus confidential information and Sonatus
# retains all right, title and interest, including, without limitation, all
# patent rights, copyrights, trademarks and trade secrets, in and to the
# source code file and any portion thereof, including, without limitation, any
# copy or derivative work of the source code file (or any portion thereof) and
# any update thereto. No copies may be made of the source code file [and the
# source code file shall be destroyed or returned to Sonatus at the request of
# Sonatus].
import os
import sys
import time
import abc
import traceback
import inspect
from operator import attrgetter

from typing import List, Optional, Any, Dict, Tuple

class TestStepException(Exception):
    def __init__(self, real_ex, step):
        super().__init__()
        self.real_ex = real_ex
        self.step = step


class Case(object):
    """A class-level decorator for specifying the case ID covered by this test file / class."""
    def __init__(self, desc):
        self.desc = desc
        self.case_name = ''

    def __call__(self, cls):
        class Wrapped(cls):
            case_name = cls.__name__
            desc = self.desc

            # anything decorated with @Case exposes this, which returns everything annotated with @Step
            def get_steps(self):
                ret = {}
                for _, method in inspect.getmembers(cls):
                    if hasattr(method, "step_id"):
                        step_id = method.step_id
                        if step_id not in ret:
                            ret[step_id] = method
                        else:
                            # Duplicate step found
                            assert False, f'Duplicate step {step_id} in {self.case_name}'
                return list(ret.values())

        return Wrapped

class Step(object):
    """A function-level decorator for specifying which step this is in the test run."""
    def __init__(self, step_id_: int):
        self.step_id = step_id_

    def __call__(self, f):
        f.step_id = self.step_id
        return f

class Test(abc.ABC):
    """Base test class for tests
    """
    @abc.abstractmethod
    def setup(self) -> None:
        pass

    @abc.abstractmethod
    def teardown(self) -> None:
        pass

    def run_steps(self) -> None:
        steps: List = self.get_steps() #pylint: disable=no-member
        steps.sort(key=attrgetter('step_id'))

        try:
            print("\nStep 0: setup")
            step_id = 0
            self.setup()
            for step in steps:
                print(f'\nStep {step.step_id}: {step.__name__}')
                step_id = step.step_id
                step(self)
        except Exception as e:
            print(traceback.format_exc())
            raise TestStepException(e, step_id)

        finally:
            try:
                print(f'\nStep {step_id + 1}: teardown')
                self.teardown()
            except Exception as e:
                # Log exception and traceback of the teardown step
                print(traceback.format_exc())
                raise TestStepException(e, step_id)

    def run(self, *args, **kwargs) -> Tuple[bool, int]:
        run_id = kwargs.get('run_id', 0)
        case_name : int = self.case_name  #pylint: disable=no-member  
        desc : str = self.desc  #pylint: disable=no-member

        print(f'Starting Test {case_name} \"{desc}\" run:{run_id}')
        try:
            self.run_steps()
        except TestStepException as e:
            print(f'Test {case_name} failed at step {e.step}')
            return False, e.step

        return True, 0

def execute(test_list: List[Any], run_id: int=0):
    results = dict() # type: Dict[str, Tuple[bool, int]]

    for test_cls in test_list:
        obj = test_cls()
        res = obj.run(run_id)
        results[obj.case_name] = res

    print('\n\nTest result:')
    for case_name, result in results.items():
        if result[0]:
            result_s = 'success'
        else:
            result_s = 'failed at step {result[1]}'
        print(f'\t{case_name} {result_s}')

