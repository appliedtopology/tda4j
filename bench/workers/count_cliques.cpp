// Counts the cliques of a threshold graph by size: the cells of a Vietoris-Rips complex by dimension.
//
//   count_cliques FILE points|distance THRESHOLD MAXSIZE
//
// FILE holds one point per line (points) or a full distance matrix (distance), numbers separated by blanks or commas,
// `#` lines skipped. Two vertices are joined when their distance is at most THRESHOLD, with a relative margin of 1e-9
// so that an edge whose length IS the threshold (the enclosing radius) stays in whatever the last bit of the sum says.
// A distance matrix is read from its lower triangle, d(i, j) = D[max][min], as ripser.cpp and TDA4j do.
//
// The count is exact and complete: every clique of every size up to MAXSIZE is counted, none estimated or taken from
// a formula, and no engine's shortcut (apparent pairs, clearing, an implicit complex) is involved. A clique of size k is
// a (k - 1)-simplex. The method is kClist (Danisch, Balalau and Sozio, WWW 2018) on a degeneracy ordering, with the
// candidate sets as bitsets, so the last level of the recursion is a popcount and never visits the cliques it counts.
//
// stdout: one JSON line {"n", "edges", "degeneracy", "counts": [cliques of size 1, 2, ...] as strings, "seconds",
// "threads"}. stderr: one `graph: ...` line as soon as the graph is built, so a run that is killed has still reported
// its vertices and edges.
//
//   g++ -O3 -march=native -fopenmp -o count_cliques count_cliques.cpp
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#ifdef _OPENMP
#include <omp.h>
#endif

typedef unsigned __int128 u128;
typedef unsigned long long u64;

static std::string to_string(u128 x) {
  if (x == 0) return "0";
  std::string s;
  while (x > 0) {
    s.insert(s.begin(), char('0' + int(x % 10)));
    x /= 10;
  }
  return s;
}

static std::vector<std::vector<double>> read_table(const char* path) {
  std::ifstream in(path);
  if (!in) {
    std::fprintf(stderr, "count_cliques: cannot read %s\n", path);
    std::exit(2);
  }
  std::vector<std::vector<double>> rows;
  std::string line;
  while (std::getline(in, line)) {
    size_t a = line.find_first_not_of(" \t\r");
    if (a == std::string::npos || line[a] == '#') continue;
    for (char& c : line)
      if (c == ',') c = ' ';
    std::istringstream ss(line);
    std::vector<double> row;
    double x;
    while (ss >> x) row.push_back(x);
    rows.push_back(std::move(row));
  }
  return rows;
}

struct Local {
  int K;
  int words;
  const u64* rows;
  std::vector<std::vector<u64>> buf;  // buf[s]: the candidate set after a clique of size s
  std::vector<u128> cnt;              // cnt[s]: cliques of size s
};

// `cand` holds the vertices that extend a clique of size s: all adjacent to all of it and later in the ordering than
// its last vertex. Its words below `from` are zero. Counts the cliques of size s + 1 and recurses.
static void rec(Local& L, int s, const u64* cand, int from) {
  u64 pc = 0;
  for (int k = from; k < L.words; k++) pc += __builtin_popcountll(cand[k]);
  L.cnt[s + 1] += pc;
  if (s + 1 >= L.K || pc == 0) return;
  u64* next = L.buf[s + 1].data();
  for (int k = from; k < L.words; k++) {
    u64 bits = cand[k];
    while (bits) {
      int w = k * 64 + __builtin_ctzll(bits);
      bits &= bits - 1;
      const u64* row = L.rows + size_t(w) * L.words;
      for (int j = w / 64; j < L.words; j++) next[j] = cand[j] & row[j];
      rec(L, s + 1, next, w / 64);
    }
  }
}

int main(int argc, char** argv) {
  if (argc != 5) {
    std::fprintf(stderr, "usage: count_cliques FILE points|distance THRESHOLD MAXSIZE\n");
    return 2;
  }
  auto t0 = std::chrono::steady_clock::now();
  std::string format = argv[2];
  double threshold = std::strtod(argv[3], nullptr);
  int K = std::atoi(argv[4]);
  if (K < 1 || (format != "points" && format != "distance")) {
    std::fprintf(stderr, "count_cliques: MAXSIZE >= 1 and format points|distance\n");
    return 2;
  }
  auto table = read_table(argv[1]);
  int n = (int)table.size();
  double limit = threshold * (1 + 1e-9);

  // Forward lists by index, then the whole adjacency.
  std::vector<std::vector<int>> up(n);
  if (format == "points") {
    int d = n ? (int)table[0].size() : 0;
    std::vector<double> X((size_t)n * d);
    for (int i = 0; i < n; i++) {
      if ((int)table[i].size() != d) {
        std::fprintf(stderr, "count_cliques: row %d has %zu coordinates, row 0 has %d\n", i, table[i].size(), d);
        return 2;
      }
      for (int k = 0; k < d; k++) X[(size_t)i * d + k] = table[i][k];
    }
    double limit2 = limit * limit;
#pragma omp parallel for schedule(dynamic, 16)
    for (int i = 0; i < n; i++) {
      const double* a = &X[(size_t)i * d];
      for (int j = i + 1; j < n; j++) {
        const double* b = &X[(size_t)j * d];
        double s = 0;
        for (int k = 0; k < d && s <= limit2; k++) {
          double t = a[k] - b[k];
          s += t * t;
        }
        if (s <= limit2) up[i].push_back(j);
      }
    }
  } else {
    for (int i = 0; i < n; i++)
      if ((int)table[i].size() != n) {
        std::fprintf(stderr, "count_cliques: distance matrix row %d has %zu entries, expected %d\n", i,
                     table[i].size(), n);
        return 2;
      }
#pragma omp parallel for schedule(dynamic, 16)
    for (int i = 0; i < n; i++)
      for (int j = i + 1; j < n; j++)
        if (table[j][i] <= limit) up[i].push_back(j);  // lower triangle: D[max][min]
  }
  table.clear();
  table.shrink_to_fit();
  std::vector<std::vector<int>> adj(n);
  u64 edges = 0;
  for (int i = 0; i < n; i++) {
    edges += up[i].size();
    adj[i].insert(adj[i].end(), up[i].begin(), up[i].end());
  }
  for (int i = 0; i < n; i++)
    for (int j : up[i]) adj[j].push_back(i);
  up.clear();
  up.shrink_to_fit();

  // Degeneracy ordering (Batagelj and Zaversnik): repeatedly remove a vertex of least remaining degree.
  std::vector<int> deg(n), vert(n), pos(n), rank(n);
  int maxdeg = 0;
  for (int v = 0; v < n; v++) {
    deg[v] = (int)adj[v].size();
    maxdeg = std::max(maxdeg, deg[v]);
  }
  std::vector<int> bin(maxdeg + 2, 0);
  for (int v = 0; v < n; v++) bin[deg[v]]++;
  int start = 0;
  for (int d = 0; d <= maxdeg; d++) {
    int num = bin[d];
    bin[d] = start;
    start += num;
  }
  for (int v = 0; v < n; v++) {
    pos[v] = bin[deg[v]];
    vert[pos[v]] = v;
    bin[deg[v]]++;
  }
  for (int d = maxdeg; d >= 1; d--) bin[d] = bin[d - 1];
  bin[0] = 0;
  int degeneracy = 0;
  for (int i = 0; i < n; i++) {
    int v = vert[i];
    degeneracy = std::max(degeneracy, deg[v]);
    for (int u : adj[v]) {
      if (deg[u] > deg[v]) {
        int du = deg[u], pu = pos[u], pw = bin[du], w = vert[pw];
        if (u != w) {
          pos[u] = pw;
          pos[w] = pu;
          vert[pu] = w;
          vert[pw] = u;
        }
        bin[du]++;
        deg[u]--;
      }
    }
  }
  for (int i = 0; i < n; i++) rank[vert[i]] = i;

  // Forward neighbours (later in the ordering), sorted by rank; the full adjacency is not needed any more.
  std::vector<std::vector<int>> fwd(n);
  for (int v = 0; v < n; v++) {
    for (int u : adj[v])
      if (rank[u] > rank[v]) fwd[v].push_back(u);
    std::sort(fwd[v].begin(), fwd[v].end(), [&](int a, int b) { return rank[a] < rank[b]; });
  }
  adj.clear();
  adj.shrink_to_fit();
  int maxfwd = 0;
  for (int v = 0; v < n; v++) maxfwd = std::max(maxfwd, (int)fwd[v].size());
  std::fprintf(stderr, "graph: n=%d edges=%llu degeneracy=%d\n", n, edges, degeneracy);

  std::vector<u128> total(K + 2, 0);
  int threads = 1;
#pragma omp parallel
  {
#ifdef _OPENMP
#pragma omp single
    threads = omp_get_num_threads();
#endif
    Local L;
    L.K = K;
    L.cnt.assign(K + 2, 0);
    int maxwords = (maxfwd + 63) / 64;
    L.buf.assign(K + 2, std::vector<u64>(std::max(1, maxwords)));
    std::vector<int> where(n, -1);
    std::vector<u64> rows;
#pragma omp for schedule(dynamic, 4)
    for (int v = 0; v < n; v++) {
      const std::vector<int>& members = fwd[v];
      int m = (int)members.size();
      L.cnt[1] += 1;
      L.cnt[2] += m;
      if (K < 3 || m < 2) continue;
      int words = (m + 63) / 64;
      L.words = words;
      rows.assign((size_t)m * words, 0);
      for (int i = 0; i < m; i++) where[members[i]] = i;
      for (int i = 0; i < m; i++)
        for (int w : fwd[members[i]]) {
          int j = where[w];
          if (j > i) rows[(size_t)i * words + j / 64] |= u64(1) << (j % 64);
        }
      for (int i = 0; i < m; i++) where[members[i]] = -1;
      L.rows = rows.data();
      for (int w = 0; w < m; w++) rec(L, 2, rows.data() + (size_t)w * words, w / 64);
    }
#pragma omp critical
    for (int s = 1; s <= K; s++) total[s] += L.cnt[s];
  }

  double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  std::printf("{\"n\":%d,\"edges\":%llu,\"degeneracy\":%d,\"counts\":[", n, edges, degeneracy);
  for (int s = 1; s <= K; s++) std::printf("%s\"%s\"", s > 1 ? "," : "", to_string(total[s]).c_str());
  std::printf("],\"seconds\":%.3f,\"threads\":%d}\n", seconds, threads);
  return 0;
}
