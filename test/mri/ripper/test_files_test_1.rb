require_relative 'assert_parse_files.rb'
class TestRipper::Generic
  # Modified for JRuby's copy of CRuby tests
  Dir["#{SRCDIR}/test/mri/[-a-n]*/"].each do |dir|
    dir = dir[(SRCDIR.length+1)..-2]
    define_method("test_parse_files:#{dir}") do
      assert_parse_files(dir)
    end
  end
end
