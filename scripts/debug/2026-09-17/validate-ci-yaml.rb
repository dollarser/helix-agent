#!/usr/bin/env ruby
# Parse workflow syntax without executing actions or changing remote state.
require 'yaml'
root = File.expand_path('../../..', __dir__)
workflow = YAML.load_file(File.join(root, '.github/workflows/ci.yml'))
abort 'Workflow jobs missing' unless workflow.fetch('jobs').is_a?(Hash)
puts "Workflow YAML parsed: #{workflow.fetch('jobs').keys.join(', ')}"
