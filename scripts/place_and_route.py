import subprocess

import errno
from scipy import stats
import copy
import itertools
import random

import re
import csv

import sys
import os
import glob
import shutil


def silentremove(filename):
    try:
        os.remove(filename)
    except OSError as e:
        if e.errno != errno.ENOENT: # errno.ENOENT = no such file or directory
            raise # re-raise exception if a different error occured


class Caller:

    def __init__(self, circuits):
        self.circuits = circuits.split(' ')


    def call_circuit(self, command, circuit, seed):
        circuit_command = copy.deepcopy(command)
        for i in range(len(circuit_command)):
            circuit_command[i] = circuit_command[i].replace('{circuit}', circuit).replace('{seed}', str(seed))

        p = subprocess.Popen(circuit_command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        # Return output
        out, err = p.communicate()
        return out.decode('utf-8'), err.decode('utf-8')


    def call_all_circuits(self, command, num_random_seeds):

        self.results = {}
        for circuit in self.circuits:
            self.results[circuit] = {}
            for metric in self.metrics:
                self.results[circuit][metric] = []

        random.seed(1)
        self.seeds = [random.randrange(2**31 - 1) for i in range(num_random_seeds)]
        for seed in self.seeds:
            self.call_all_circuits_with_seed(command, seed)


    def call_all_circuits_with_seed(self, command, seed):
        stats_pattern = re.compile(self.stats_regex, re.DOTALL)
        self.command = command

        for circuit in self.circuits:
            print('  ' + circuit)
            out, err = self.call_circuit(command, circuit, seed)

            if err:
                print(err)
                print('There was a problem with circuit "{0}"'.format(circuit))
                sys.exit(1)

            # Get and save statistics
            match = stats_pattern.search(out)

            if match is None:
                print(out)
                print('Failed to match pattern: {0}'.format(self.stats_regex))
                sys.exit(1)

            for metric in self.metrics:
                group_name = metric.lower().replace(' ', '_')
                self.results[circuit][metric].append(float(match.group(group_name)))


    def save_results(self, filename):
        rows = self.get_results()
        rows.append([])
        rows.append(['geomeans'] + self.get_geomeans())

        _file = open(filename, 'w')
        csv_writer = csv.writer(_file)
        csv_writer.writerows(rows)
        _file.close()


    def get_command(self):
        return ' '.join(self.command)

    def get_metrics(self):
        return self.metrics

    def get_seeds(self):
        return self.seeds


    def get_results(self):
        results = [['benchmark'] + self.metrics + [self.get_command()]]

        # Print the results for each circuit
        for circuit in sorted(self.results):
            row = [circuit]
            for metric in self.metrics:
                row.append(geomean(self.results[circuit][metric]))

            results.append(row)

        return results


    def get_geomeans(self):
        return [self.get_geomean(metric) for metric in self.metrics]

    def get_geomean(self, metric):
        metric_results = []
        for circuit in self.circuits:
            metric_results += self.results[circuit][metric]

        return stats.gmean(metric_results)



class PlaceCaller(Caller):

    metrics = ['runtime', 'BB cost', 'max delay']
    stats_regex = r'.*time\s+\|\s+(?P<runtime>[0-9.e+-]+).*BB cost\s+\|\s+(?P<bb_cost>[0-9.e+-]+).*max delay\s+\|\s+(?P<max_delay>[0-9.e+-]+)'

    def __init__(self, architecture, circuits_folder, circuits):
        Caller.__init__(self, circuits)

        self.architecture = architecture
        self.circuit = os.path.join(circuits_folder, '{circuit}.blif')


    def place_all(self, options, num_random_seeds):
        command = self.build_command(self.architecture, self.circuit, options)

        self.call_all_circuits(command, num_random_seeds)

        shutil.rmtree('tmp')


    def build_command(self, architecture, circuit, options):
        return [
            'java',
            '-cp', 'bin',
            'interfaces.CLI',
            architecture,
            circuit,
            '--output_place_file', 'tmp/{circuit}.place',
            '--random_seed', '{seed}'
        ] + options


class ParameterSweeper:

    def __init__(self, architecture, circuits_folder, circuits):
        self.architecture = architecture
        self.circuits_folder = circuits_folder
        self.circuits = circuits

    def sweep(self, fixed_options, variable_options, num_random_seeds):
        self.build_option_sets(variable_options)

        self.callers = []
        for option_set in self.option_sets:
            options = fixed_options + option_set
            caller = PlaceCaller(self.architecture, self.circuits_folder, self.circuits)
            caller.place_all(options, num_random_seeds)
            self.callers.append(caller)


    def save_results(self, filename):
        seeds_string = ', '.join(str(seed) for seed in self.callers[0].get_seeds())
        rows = [['Random seeds: ' + seeds_string]]

        rows += self.get_pareto_table('BB cost')
        rows.append([])

        rows += self.get_pareto_table('max delay')
        rows += [''] * 2

        for caller in self.callers:
            rows += caller.get_results()
            rows.append([])

        _file = open(filename, 'w')
        csv_writer = csv.writer(_file)
        csv_writer.writerows(rows)
        _file.close()


    def get_pareto_table(self, metric):
        rows = [[metric] + [' '.join(option_set) for option_set in self.option_sets]]
        for i in range(len(self.callers)):
            row = [self.callers[i].get_geomean('runtime')]
            row += [''] * i
            row.append(self.callers[i].get_geomean(metric))
            rows.append(row)

        return rows


    def build_option_sets(self, option_ranges):
        self.option_sets = []

        option_names = option_ranges.keys()
        option_values = itertools.product(*option_ranges.values())

        self.option_sets = []
        for option_value in option_values:
            option_set = []
            for i in range(len(option_names)):
                option_set += [option_names[i], str(option_value[i])]

            self.option_sets.append(option_set)
