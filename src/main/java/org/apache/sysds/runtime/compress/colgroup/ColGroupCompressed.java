/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.compress.colgroup;

import org.apache.sysds.runtime.DMLScriptException;
import org.apache.sysds.runtime.compress.colgroup.dictionary.ADictionary;
import org.apache.sysds.runtime.compress.colgroup.dictionary.MatrixBlockDictionary;
import org.apache.sysds.runtime.data.SparseBlock;
import org.apache.sysds.runtime.functionobjects.Builtin;
import org.apache.sysds.runtime.functionobjects.Builtin.BuiltinCode;
import org.apache.sysds.runtime.functionobjects.KahanPlus;
import org.apache.sysds.runtime.functionobjects.KahanPlusSq;
import org.apache.sysds.runtime.functionobjects.Multiply;
import org.apache.sysds.runtime.functionobjects.Plus;
import org.apache.sysds.runtime.functionobjects.ReduceAll;
import org.apache.sysds.runtime.functionobjects.ReduceCol;
import org.apache.sysds.runtime.functionobjects.ReduceRow;
import org.apache.sysds.runtime.functionobjects.ValueFunction;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.AggregateUnaryOperator;

/**
 * Base class for column groups encoded Encoded in a compressed manner.
 */
public abstract class ColGroupCompressed extends AColGroup {

	private static final long serialVersionUID = 6219835795420081223L;

	protected ColGroupCompressed() {
		super();
	}

	protected ColGroupCompressed(int[] colIndices) {
		super(colIndices);
	}

	public abstract double[] getValues();

	public abstract boolean isLossy();

	protected abstract double computeMxx(double c, Builtin builtin);

	protected abstract void computeColMxx(double[] c, Builtin builtin);

	protected abstract void computeSum(double[] c, int nRows, boolean square);

	protected abstract void computeRowSums(double[] c, boolean square, int rl, int ru);

	protected abstract void computeColSums(double[] c, int nRows, boolean square);

	protected abstract void computeRowMxx(double[] c, Builtin builtin, int rl, int ru);

	protected abstract void computeProduct(double[] c, int nRows);

	protected abstract void computeRowProduct(double[] c, int rl, int ru);

	protected abstract void computeColProduct(double[] c, int nRows);

	@Override
	public double getMin() {
		return computeMxx(Double.POSITIVE_INFINITY, Builtin.getBuiltinFnObject(BuiltinCode.MIN));
	}

	@Override
	public double getMax() {
		return computeMxx(Double.NEGATIVE_INFINITY, Builtin.getBuiltinFnObject(BuiltinCode.MAX));
	}

	@Override
	public void computeColSums(double[] c, int nRows) {
		computeColSums(c, nRows, false);
	}

	@Override
	public final void unaryAggregateOperations(AggregateUnaryOperator op, double[] c, int nRows, int rl, int ru) {
		final ValueFunction fn = op.aggOp.increOp.fn;
		if(fn instanceof Plus || fn instanceof KahanPlus || fn instanceof KahanPlusSq) {
			boolean square = fn instanceof KahanPlusSq;
			if(op.indexFn instanceof ReduceAll)
				computeSum(c, nRows, square);
			else if(op.indexFn instanceof ReduceCol)
				computeRowSums(c, square, rl, ru);
			else if(op.indexFn instanceof ReduceRow)
				computeColSums(c, nRows, square);
		}
		else if(fn instanceof Multiply) {
			if(op.indexFn instanceof ReduceAll)
				computeProduct(c, nRows);
			else if(op.indexFn instanceof ReduceCol)
				computeRowProduct(c, rl, ru);
			else if(op.indexFn instanceof ReduceRow)
				computeColProduct(c, nRows);
		}
		else if(fn instanceof Builtin) {
			Builtin bop = (Builtin) fn;
			BuiltinCode bopC = bop.getBuiltinCode();
			if(bopC == BuiltinCode.MAX || bopC == BuiltinCode.MIN) {
				if(op.indexFn instanceof ReduceAll)
					c[0] = computeMxx(c[0], bop);
				else if(op.indexFn instanceof ReduceCol)
					computeRowMxx(c, bop, rl, ru);
				else if(op.indexFn instanceof ReduceRow)
					computeColMxx(c, bop);
			}
			else {
				throw new DMLScriptException("unsupported builtin type: " + bop);
			}
		}
		else {
			throw new DMLScriptException("Unknown UnaryAggregate operator on CompressedMatrixBlock");
		}
	}

	@Override
	public final void tsmm(MatrixBlock ret, int nRows) {
		double[] result = ret.getDenseBlockValues();
		int numColumns = ret.getNumColumns();
		tsmm(result, numColumns, nRows);
	}

	protected abstract void tsmm(double[] result, int numColumns, int nRows);

	protected static void tsmm(double[] result, int numColumns, int[] counts, ADictionary dict, int[] colIndexes) {
		dict = dict.getAsMatrixBlockDictionary(colIndexes.length);
		if(dict instanceof MatrixBlockDictionary) {
			MatrixBlockDictionary mbd = (MatrixBlockDictionary) dict;
			MatrixBlock mb = mbd.getMatrixBlock();
			if(mb.isEmpty())
				return;
			else if(mb.isInSparseFormat())
				tsmmSparse(result, numColumns, mb.getSparseBlock(), counts, colIndexes);
			else
				tsmmDense(result, numColumns, mb.getDenseBlockValues(), counts, colIndexes);
		}
		else
			tsmmDense(result, numColumns, dict.getValues(), counts, colIndexes);

	}

	protected static void tsmmDense(double[] result, int numColumns, double[] values, int[] counts, int[] colIndexes) {
		if(values == null)
			return;
		final int nCol = colIndexes.length;
		final int nRow = values.length / colIndexes.length;
		for(int k = 0; k < nRow; k++) {
			final int offTmp = nCol * k;
			final int scale = counts[k];
			for(int i = 0; i < nCol; i++) {
				final int offRet = numColumns * colIndexes[i];
				final double v = values[offTmp + i] * scale;
				if(v != 0)
					for(int j = i; j < nCol; j++)
						result[offRet + colIndexes[j]] += v * values[offTmp + j];
			}
		}
	}

	protected static void tsmmSparse(double[] result, int numColumns, SparseBlock sb, int[] counts, int[] colIndexes) {
		for(int row = 0; row < sb.numRows(); row++) {
			if(sb.isEmpty(row))
				continue;
			final int apos = sb.pos(row);
			final int alen = sb.size(row);
			final int[] aix = sb.indexes(row);
			final double[] avals = sb.values(row);
			for(int i = apos; i < apos + alen; i++) {
				final int offRet = colIndexes[aix[i]] * numColumns;
				final double val = avals[i] * counts[row];
				for(int j = i; j < apos + alen; j++) {
					result[offRet + colIndexes[aix[j]]] += val * avals[j];
				}
			}
		}
	}
}
