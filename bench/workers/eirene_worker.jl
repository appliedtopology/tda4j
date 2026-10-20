# Eirene.jl side of the cross-platform benchmark: one computation per process, same contract as
# py_worker.py (key=value arguments, one JSON line on stdout, the last barcode as dim<TAB>birth<TAB>death).
#
#   julia --project=<eirene project> eirene_worker.jl task=vr input=x.txt format=points dim=2 [threshold=1.8]
#         [p=2] [variant=bars|cycles] [warmup=2] [trials=5] [out=bars.tsv]
#
# Eirene computes over Z/2 only (its persistence routines are `persistf2...`): any other p exits with 3, "unsupported".
# variant=bars is `record="none"`, the barcode alone, as the Ripser paper's Dockerfile runs it; variant=cycles is
# `record="cyclerep"` and reads a representative cycle for every bar inside the timed computation.
using Eirene, DelimitedFiles, Printf

opts = Dict{String,String}()
for a in ARGS
    k, v = split(a, "="; limit=2)
    opts[k] = v
end
if opts["task"] != "vr" || parse(Int, get(opts, "p", "2")) != 2
    exit(3)  # unsupported
end
dim = parse(Int, opts["dim"])
warmup = parse(Int, get(opts, "warmup", "2"))
trials = parse(Int, get(opts, "trials", "5"))
variant = get(opts, "variant", "bars")
cycles = variant == "cycles"
t0 = time()
raw = readdlm(opts["input"], Float64)
points = get(opts, "format", "points") == "points"
# Eirene's "pc" model takes the points as COLUMNS; its "vr" model a distance matrix.
data = points ? permutedims(raw) : raw
# Edges up to the threshold stay (the interval [minrad, maxrad] is closed); a relative margin keeps the enclosing radius.
maxrad = haskey(opts, "threshold") ? parse(Float64, opts["threshold"]) * (1 + 1e-9) : Inf
load_s = time() - t0

function run()
    C = eirene(data; model=points ? "pc" : "vr", maxdim=dim, maxrad=maxrad,
               record=cycles ? "cyclerep" : "none")
    bars = [barcode(C; dim=k) for k in 0:dim]
    if cycles
        for k in 0:dim
            n = size(bars[k+1], 1)
            n > 0 && classrep(C; dim=k, class=collect(1:n), format="index")
        end
    end
    return bars
end

result = nothing
for _ in 1:warmup
    global result = run()
end
times = Float64[]
for _ in 1:trials
    t = time()
    global result = run()
    push!(times, time() - t)
end
if haskey(opts, "out")
    open(opts["out"], "w") do f
        for k in 0:dim, i in 1:size(result[k+1], 1)
            d = result[k+1][i, 2]
            @printf(f, "%d\t%.17g\t%s\n", k, result[k+1][i, 1], isfinite(d) ? @sprintf("%.17g", d) : "inf")
        end
    end
end
bars = join(["\"$k\":$(size(result[k+1], 1))" for k in 0:dim], ",")
println("{\"tool\":\"eirene\",\"task\":\"vr\",\"variant\":\"$variant\",\"load_s\":$load_s,",
        "\"times_s\":[", join(times, ","), "],\"bars\":{", bars, "},\"version\":\"",
        string(pkgversion(Eirene)), "\"}")
