# Dowker persistence of Hee Rhang Yoon (github.com/irishryoon/Dowker_persistence; Julia, GPL-3.0; it ships its own
# modified Eirene) as a tool of the cross-platform benchmark: same contract as eirene_worker.jl (key=value arguments, one
# JSON line on stdout, the last barcode as dim<TAB>birth<TAB>death).
#
#   julia --project=<the cloned repository> dowker_yoon_worker.jl task=dowker input=relation.txt dim=1
#         [threshold=0.35] [p=2] [dual=true] [warmup=2] [trials=5] [out=bars.tsv]
#
# The repository computes degrees 0 and 1 over Z/2 only, so dim > 1 and p != 2 exit with 3, "unsupported". `input` is the
# relation matrix (rows: the vertices of TDA4j's complex, columns: the witnesses); `compute_Witness_persistence` takes the
# matrix as it is. `threshold` is `param_max`.
# NOT RUN in the session that wrote it (no Julia there): check the first run against the TDA4j row, and the repository's
# own README says it wants Julia 1.7.2.
using DelimitedFiles, Printf

opts = Dict{String,String}()
for a in ARGS
    k, v = split(a, "="; limit=2)
    opts[k] = v
end
if opts["task"] != "dowker" || parse(Int, get(opts, "p", "2")) != 2 || parse(Int, opts["dim"]) > 1
    exit(3)  # unsupported
end
root = get(ENV, "DOWKER_YOON_PROJECT", pwd())
include(joinpath(root, "Eirene_var.jl"))
include(joinpath(root, "dowker_persistence.jl"))
using .Eirene_var
using .Dowker

dim = parse(Int, opts["dim"])
warmup = parse(Int, get(opts, "warmup", "2"))
trials = parse(Int, get(opts, "trials", "5"))
t0 = time()
D = readdlm(opts["input"], Float64)
get(opts, "dual", "false") == "true" && (D = permutedims(D))
param_max = haskey(opts, "threshold") ? parse(Float64, opts["threshold"]) : Inf
load_s = time() - t0

function run()
    W = Dowker.compute_Witness_persistence(D; maxdim=dim, param_max=param_max)
    return [Eirene_var.barcode(W["eirene_output"], dim=k) for k in 0:dim]
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
println("{\"tool\":\"dowker-yoon\",\"task\":\"dowker\",\"load_s\":$load_s,\"times_s\":[", join(times, ","),
        "],\"bars\":{", bars, "},\"version\":\"git\"}")
