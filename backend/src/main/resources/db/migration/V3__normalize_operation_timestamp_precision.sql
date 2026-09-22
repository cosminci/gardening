update operation
set date = case
    when instr(date, '.') = 0
        then substr(date, 1, length(date) - 1) || '.000000000Z'
    else substr(date, 1, length(date) - 1)
        || substr('000000000', 1, 9 - (length(date) - instr(date, '.') - 1))
        || 'Z'
end;
