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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import org.apache.commons.lang.NotImplementedException;
import org.apache.sysds.runtime.compress.DMLCompressionException;
import org.apache.sysds.runtime.compress.colgroup.dictionary.ADictionary;
import org.apache.sysds.runtime.compress.colgroup.dictionary.Dictionary;
import org.apache.sysds.runtime.compress.colgroup.dictionary.DictionaryFactory;
import org.apache.sysds.runtime.compress.colgroup.dictionary.MatrixBlockDictionary;
import org.apache.sysds.runtime.data.DenseBlock;
import org.apache.sysds.runtime.functionobjects.Builtin;
import org.apache.sysds.runtime.matrix.data.LibMatrixMult;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.BinaryOperator;
import org.apache.sysds.runtime.matrix.operators.ScalarOperator;

public class ColGroupConst extends ColGroupCompressed {

	private static final long serialVersionUID = -7387793538322386611L;

	protected ADictionary _dict;

	/**
	 * Constructor for serialization
	 * 
	 * @param numRows Number of rows contained
	 */
	protected ColGroupConst() {
		super();
	}

	/**
	 * Constructs an Constant Colum Group, that contains only one tuple, with the given value.
	 * 
	 * @param colIndices The Colum indexes for the column group.
	 * @param dict       The dictionary containing one tuple for the entire compression.
	 */
	public ColGroupConst(int[] colIndices, ADictionary dict) {
		super(colIndices);
		this._dict = dict;
	}

	@Override
	protected void computeRowSums(double[] c, boolean square, int rl, int ru) {
		double vals = _dict.sumAllRowsToDouble(square, _colIndexes.length)[0];
		for(int rix = rl; rix < ru; rix++)
			c[rix] += vals;
	}

	@Override
	protected void computeRowMxx(double[] c, Builtin builtin, int rl, int ru) {
		double value = _dict.aggregateTuples(builtin, _colIndexes.length)[0];
		for(int i = rl; i < ru; i++)
			c[i] = builtin.execute(c[i], value);
	}

	@Override
	public CompressionType getCompType() {
		return CompressionType.CONST;
	}

	@Override
	public ColGroupType getColGroupType() {
		return ColGroupType.CONST;
	}

	@Override
	public void decompressToBlock(MatrixBlock target, int rl, int ru, int offT) {
		final DenseBlock db = target.getDenseBlock();
		for(int i = rl; i < ru; i++, offT++) {
			final double[] c = db.values(offT);
			final int off = db.pos(offT);
			for(int j = 0; j < _colIndexes.length; j++)
				c[off + _colIndexes[j]] += _dict.getValue(j);
		}
	}

	@Override
	public double get(int r, int c) {
		return _dict.getValue(Arrays.binarySearch(_colIndexes, c));
	}

	@Override
	public AColGroup scalarOperation(ScalarOperator op) {
		return new ColGroupConst(_colIndexes, _dict.clone().apply(op));
	}

	@Override
	public AColGroup binaryRowOp(BinaryOperator op, double[] v, boolean sparseSafe, boolean left) {
		return new ColGroupConst(_colIndexes, _dict.clone().applyBinaryRowOp(op, v, true, _colIndexes, left));
	}

	@Override
	public void countNonZerosPerRow(int[] rnnz, int rl, int ru) {
		double[] values = _dict.getValues();
		int base = 0;
		for(int i = 0; i < values.length; i++) {
			base += values[i] == 0 ? 0 : 1;
		}
		for(int i = 0; i < ru - rl; i++) {
			rnnz[i] = base;
		}
	}

	/**
	 * Take the values in this constant column group and add to the given constV. This allows us to completely ignore
	 * this column group for future calculations.
	 * 
	 * @param constV The output columns.
	 */
	public void addToCommon(double[] constV) {
		final double[] values = _dict.getValues();
		if(values != null && constV != null)
			for(int i = 0; i < _colIndexes.length; i++)
				constV[_colIndexes[i]] += values[i];
	}

	@Override
	public double[] getValues() {
		return _dict != null ? _dict.getValues() : null;
	}

	@Override
	public final boolean isLossy() {
		return _dict.isLossy();
	}

	@Override
	protected double computeMxx(double c, Builtin builtin) {
		return _dict.aggregate(c, builtin);
	}

	@Override
	protected void computeColMxx(double[] c, Builtin builtin) {
		_dict.aggregateCols(c, builtin, _colIndexes);
	}

	@Override
	protected void computeSum(double[] c, int nRows, boolean square) {
		if(_dict != null)
			if(square)
				c[0] += _dict.sumsq(new int[] {nRows}, _colIndexes.length);
			else
				c[0] += _dict.sum(new int[] {nRows}, _colIndexes.length);
	}

	@Override
	protected void computeColSums(double[] c, int nRows, boolean square) {
		_dict.colSum(c, new int[] {nRows}, _colIndexes, square);
	}

	@Override
	public int getNumValues() {
		return 1;
	}

	@Override
	public MatrixBlock getValuesAsBlock() {
		_dict = _dict.getAsMatrixBlockDictionary(_colIndexes.length);
		MatrixBlock ret = ((MatrixBlockDictionary) _dict).getMatrixBlock();
		return ret;
	}

	@Override
	public AColGroup rightMultByMatrix(MatrixBlock right) {
		if(right.isEmpty())
			return null;
		final int rr = right.getNumRows();
		final int cr = right.getNumColumns();
		if(_colIndexes.length == rr) {
			MatrixBlock left = getValuesAsBlock();
			MatrixBlock ret = new MatrixBlock(1, cr, false);
			LibMatrixMult.matrixMult(left, right, ret);
			ADictionary d = new MatrixBlockDictionary(ret);
			return ColGroupFactory.getColGroupConst(cr, d);
		}
		else {
			throw new NotImplementedException();
		}
	}

	@Override
	public void tsmm(double[] result, int numColumns, int nRows) {
		tsmm(result, numColumns, new int[] {nRows}, _dict, _colIndexes);
	}

	@Override
	public void leftMultByMatrix(MatrixBlock matrix, MatrixBlock result, int rl, int ru) {
		throw new NotImplementedException();
	}

	@Override
	public void leftMultByAColGroup(AColGroup lhs, MatrixBlock result) {
		throw new DMLCompressionException("Should not be called");
	}

	@Override
	public void tsmmAColGroup(AColGroup other, MatrixBlock result) {
		throw new DMLCompressionException("Should not be called");
	}

	@Override
	public boolean isDense() {
		return true;
	}

	@Override
	protected AColGroup sliceSingleColumn(int idx) {
		int[] colIndexes = new int[] {0};
		double v = _dict.getValue(idx);
		if(v == 0)
			return new ColGroupEmpty(colIndexes);
		else {
			ADictionary retD = new Dictionary(new double[] {_dict.getValue(idx)});
			return new ColGroupConst(colIndexes, retD);
		}
	}

	@Override
	protected AColGroup sliceMultiColumns(int idStart, int idEnd, int[] outputCols) {
		ADictionary retD = _dict.sliceOutColumnRange(idStart, idEnd, _colIndexes.length);
		return new ColGroupConst(outputCols, retD);
	}

	@Override
	public AColGroup copy() {
		return new ColGroupConst(_colIndexes, _dict.clone());
	}

	@Override
	public boolean containsValue(double pattern) {
		return _dict.containsValue(pattern);
	}

	@Override
	public long getNumberNonZeros(int nRows) {
		return _dict.getNumberNonZeros(new int[] {nRows}, _colIndexes.length);
	}

	@Override
	public AColGroup replace(double pattern, double replace) {
		ADictionary replaced = _dict.replace(pattern, replace, _colIndexes.length);
		return new ColGroupConst(_colIndexes, replaced);
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		super.readFields(in);
		_dict = DictionaryFactory.read(in);
	}

	@Override
	public void write(DataOutput out) throws IOException {
		super.write(out);
		_dict.write(out);
	}

	@Override
	public long getExactSizeOnDisk() {
		long ret = super.getExactSizeOnDisk();
		if(_dict != null)
			ret += _dict.getExactSizeOnDisk();

		return ret;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(super.toString());
		sb.append(String.format("\n%15s ", "Values: " + _dict.getClass().getSimpleName()));
		sb.append(_dict.getString(_colIndexes.length));
		return sb.toString();
	}

	@Override
	protected void computeProduct(double[] c, int nRows) {
		final double[] vals = getValues();
		for(int i = 0; i < _colIndexes.length; i++) {
			double v = vals[i];
			if(v != 0)
				c[0] *= Math.pow(v, nRows);
			else
				c[0] = 0;
		}
	}

	@Override
	protected void computeRowProduct(double[] c, int rl, int ru) {
		throw new NotImplementedException();
	}

	@Override
	protected void computeColProduct(double[] c, int nRows) {
		throw new NotImplementedException();

	}
}
