// Writes the filtered boundary matrix of a Vietoris-Rips complex in PHAT's binary format, for the benchmark's `phat` rows.
//
//   make_phat UPPER_DIM INPUT.dipha OUTPUT.phat
//
// INPUT is a DIPHA sparse (or full) distance matrix (bench/dipha_io.py). The complex is DIPHA's own, to dimension
// UPPER_DIM, in DIPHA's filtration order (value, then dimension, then the combinatorial index), so PHAT reduces what DIPHA
// reduces. Written next to OUTPUT: OUTPUT.values (DIPHA's filtration value of each column, a float64 each: half the
// largest distance) and OUTPUT.dims (the dimension of each column, an int64 each). The matrix is the primal boundary
// matrix; PHAT's own --dualize does the dual computation.
//
// Adapted from DIPHA's src/create_phat_filtration.cpp (LGPL v3, IST Austria), which writes the matrix but not the values.
// Build with MPI like DIPHA:
//
//   mpicxx -O3 -DNDEBUG -std=c++11 -I$DIPHA/include -I$DIPHA/externals -o make_phat make_phat.cpp
#include <dipha/includes.h>

template< typename Complex >
void make_phat(const std::string& input_filename, int64_t upper_dim, const std::string& output_filename)
{
  Complex complex;
  complex.load_binary(input_filename, upper_dim);
  dipha::data_structures::distributed_vector< int64_t > filtration_to_cell_map;
  dipha::algorithms::get_filtration_to_cell_map(complex, false, filtration_to_cell_map);
  dipha::data_structures::distributed_vector< int64_t > cell_to_filtration_map;
  dipha::algorithms::get_cell_to_filtration_map(complex.get_num_cells(), filtration_to_cell_map, cell_to_filtration_map);

  const int64_t nr_columns = complex.get_num_cells();
  std::vector< std::vector< int64_t > > matrix(nr_columns);
  std::vector< int64_t > dims(nr_columns, -1);

  for (int64_t cur_dim = 0; cur_dim <= complex.get_max_dim(); cur_dim++)
  {
    dipha::data_structures::flat_column_stack unreduced_columns;
    dipha::algorithms::generate_unreduced_columns(complex, filtration_to_cell_map, cell_to_filtration_map, cur_dim, false,
                                                  unreduced_columns);
    dipha::data_structures::heap_column col;
    while (!unreduced_columns.empty())
    {
      int64_t index;
      unreduced_columns.pop(index, col);
      std::sort(col.begin(), col.end());
      matrix[index] = col;
      dims[index] = cur_dim;
    }
  }

  std::ofstream output_stream(output_filename.c_str(), std::ios_base::binary | std::ios_base::out);
  output_stream.write((char*)&nr_columns, sizeof(int64_t));
  for (int64_t cur_col = 0; cur_col < nr_columns; cur_col++)
  {
    int64_t cur_dim = dims[cur_col];
    output_stream.write((char*)&cur_dim, sizeof(int64_t));
    int64_t cur_nr_rows = matrix[cur_col].size();
    output_stream.write((char*)&cur_nr_rows, sizeof(int64_t));
    for (int64_t cur_row_idx = 0; cur_row_idx < cur_nr_rows; cur_row_idx++)
    {
      int64_t cur_row = matrix[cur_col][cur_row_idx];
      output_stream.write((char*)&cur_row, sizeof(int64_t));
    }
  }
  output_stream.close();

  std::ofstream values_stream((output_filename + ".values").c_str(), std::ios_base::binary | std::ios_base::out);
  for (int64_t cur_col = 0; cur_col < nr_columns; cur_col++)
  {
    double value = complex.get_local_value(filtration_to_cell_map.get_local_value(cur_col));
    values_stream.write((char*)&value, sizeof(double));
  }
  values_stream.close();

  std::ofstream dims_stream((output_filename + ".dims").c_str(), std::ios_base::binary | std::ios_base::out);
  dims_stream.write((char*)dims.data(), nr_columns * sizeof(int64_t));
  dims_stream.close();
}

int main(int argc, char** argv)
{
  MPI_Init(&argc, &argv);
  if (dipha::mpi_utils::get_num_processes() != 1)
  {
    dipha::mpi_utils::error_printer_if_root() << "Error: supports only one process." << std::endl;
    MPI_Abort(MPI_COMM_WORLD, EXIT_FAILURE);
  }
  if (argc != 4)
  {
    std::cerr << "Usage: make_phat UPPER_DIM INPUT.dipha OUTPUT.phat" << std::endl;
    MPI_Abort(MPI_COMM_WORLD, EXIT_FAILURE);
  }
  const int64_t upper_dim = std::stoll(argv[1]);
  const std::string input_filename = argv[2];
  const std::string output_filename = argv[3];
  switch (dipha::file_types::get_file_type(input_filename))
  {
  case dipha::file_types::DISTANCE_MATRIX:
    make_phat< dipha::inputs::full_rips_complex >(input_filename, upper_dim, output_filename);
    break;
  case dipha::file_types::SPARSE_DISTANCE_MATRIX:
    make_phat< dipha::inputs::sparse_rips_complex >(input_filename, upper_dim, output_filename);
    break;
  default:
    dipha::mpi_utils::error_printer_if_root() << "make_phat reads DIPHA distance matrices, not " << input_filename << std::endl;
    MPI_Abort(MPI_COMM_WORLD, EXIT_FAILURE);
  }
  MPI_Finalize();
}
