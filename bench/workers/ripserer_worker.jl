# Ripserer.jl side of the cross-platform benchmark: one computation per process, same contract as
# py_worker.py (key=value arguments, one JSON line on stdout, the last barcode as dim<TAB>birth<TAB>death).
#
#   julia --project=bench/julia ripserer_worker.jl task=vr input=x.txt format=points dim=2 [threshold=1.8]
#         [p=2] [variant=cohomology|involuted] [warmup=2] [trials=5] [out=bars.tsv]
#
# variant=involuted asks for cycle representatives (Čufar-Virk), the analogue of TDA4j's default.
using Ripserer, DelimitedFiles, Printf

opts = Dict{String,String}()
for a in ARGS
    k, v = split(a, "="; limit=2)
    opts[k] = v
end
if opts["task"] != "vr"
    exit(3)  # unsupported
end
dim = parse(Int, opts["dim"])
p = parse(Int, get(opts, "p", "2"))
warmup = parse(Int, get(opts, "warmup", "2"))
trials = parse(Int, get(opts, "trials", "5"))
variant = get(opts, "variant", "cohomology")
t0 = time()
raw = readdlm(opts["input"], Float64)
if get(opts, "format", "points") == "distance"
    data = raw
else
    data = [Tuple(raw[i, :]) for i in 1:size(raw, 1)]
end
filtration() = haskey(opts, "threshold") ?
    Rips(data; threshold=parse(Float64, opts["threshold"])) : Rips(data)
run() = variant == "involuted" ?
    ripserer(filtration(); dim_max=dim, modulus=p, alg=:involuted, reps=true) :
    ripserer(filtration(); dim_max=dim, modulus=p)
load_s = time() - t0
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
counts = Dict{Int,Int}()
if haskey(opts, "out")
    open(opts["out"], "w") do f
        for (k, dgm) in enumerate(result), int in dgm
            d = death(int)
            @printf(f, "%d\t%.17g\t%s\n", k - 1, birth(int), isfinite(d) ? @sprintf("%.17g", d) : "inf")
        end
    end
end
for (k, dgm) in enumerate(result)
    counts[k-1] = length(dgm)
end
bars = join(["\"$k\":$(counts[k])" for k in sort(collect(keys(counts)))], ",")
println("{\"tool\":\"ripserer\",\"task\":\"vr\",\"variant\":\"$variant\",\"load_s\":$load_s,",
        "\"times_s\":[", join(times, ","), "],\"bars\":{", bars, "},\"version\":\"",
        string(pkgversion(Ripserer)), "\"}")
