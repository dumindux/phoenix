package org.apache.phoenix.calcite;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.calcite.plan.RelOptUtil.InputFinder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.io.WritableUtils;
import org.apache.phoenix.compile.ColumnProjector;
import org.apache.phoenix.compile.ExpressionProjector;
import org.apache.phoenix.compile.RowProjector;
import org.apache.phoenix.compile.TupleProjectionCompiler;
import org.apache.phoenix.coprocessor.BaseScannerRegionObserver;
import org.apache.phoenix.execute.TupleProjector;
import org.apache.phoenix.expression.ColumnExpression;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.LiteralExpression;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.schema.ColumnRef;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PNameFactory;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.ProjectedColumn;
import org.apache.phoenix.schema.TableRef;
import org.apache.phoenix.schema.KeyValueSchema.KeyValueSchemaBuilder;
import org.apache.phoenix.schema.PTable.IndexType;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.SchemaUtil;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class TableMapping {
    private final TableRef tableRef;
    private final TableRef dataTableRef;
    private final List<PColumn> mappedColumns;
    private final int extendedColumnsOffset;
    private final TableRef extendedTableRef;

    public TableMapping(PTable table) {
        this.tableRef = new TableRef(table);
        this.dataTableRef = null;
        this.mappedColumns = getMappedColumns(table);
        this.extendedColumnsOffset = mappedColumns.size();
        this.extendedTableRef = null;
    }

    public TableMapping(TableRef tableRef, TableRef dataTableRef, boolean extend) throws SQLException {
        this.tableRef = tableRef;
        this.dataTableRef = dataTableRef;
        if (!extend) {
            this.mappedColumns = getMappedColumns(tableRef.getTable());
            this.extendedColumnsOffset = mappedColumns.size();
            this.extendedTableRef = null;            
        } else {
            this.mappedColumns = Lists.newArrayList();
            this.mappedColumns.addAll(getMappedColumns(tableRef.getTable()));
            this.extendedColumnsOffset = mappedColumns.size();
            Set<String> names = Sets.newHashSet();
            for (PColumn column : this.mappedColumns) {
                names.add(column.getName().getString());
            }
            PTable dataTable = dataTableRef.getTable();
            List<PColumn> projectedColumns = new ArrayList<PColumn>();
            for (PColumn sourceColumn : dataTable.getColumns()) {
                if (!SchemaUtil.isPKColumn(sourceColumn)) {
                    String colName = IndexUtil.getIndexColumnName(sourceColumn);
                    if (!names.contains(colName)) {
                        ColumnRef sourceColumnRef =
                                new ColumnRef(dataTableRef, sourceColumn.getPosition());
                        PColumn column = new ProjectedColumn(PNameFactory.newName(colName),
                                sourceColumn.getFamilyName(), projectedColumns.size(),
                                sourceColumn.isNullable(), sourceColumnRef);
                        projectedColumns.add(column);
                    }
                }            
            }
            this.mappedColumns.addAll(projectedColumns);
            PTable extendedTable = PTableImpl.makePTable(dataTable.getTenantId(),
                    TupleProjectionCompiler.PROJECTED_TABLE_SCHEMA, dataTable.getName(),
                    PTableType.PROJECTED, null, dataTable.getTimeStamp(),
                    dataTable.getSequenceNumber(), dataTable.getPKName(), null,
                    projectedColumns, null, null, Collections.<PTable>emptyList(),
                    dataTable.isImmutableRows(), Collections.<PName>emptyList(), null, null,
                    dataTable.isWALDisabled(), false, dataTable.getStoreNulls(),
                    dataTable.getViewType(), null, null, dataTable.rowKeyOrderOptimizable(),
                    dataTable.isTransactional(), dataTable.getUpdateCacheFrequency(),
                    dataTable.getIndexDisableTimestamp());
            this.extendedTableRef = new TableRef(extendedTable);
        }
    }
    
    public TableRef getTableRef() {
        return tableRef;
    }
    
    public PTable getPTable() {
        return tableRef.getTable();
    }
    
    public TableRef getDataTableRef() {
        return dataTableRef;
    }
    
    public List<PColumn> getMappedColumns() {
        return mappedColumns;
    }
    
    public boolean hasExtendedColumns() {
        return extendedTableRef != null;
    }
    
    public ColumnExpression newColumnExpression(int index) {
        ColumnRef colRef = new ColumnRef(
                index < extendedColumnsOffset ? tableRef : extendedTableRef,
                this.mappedColumns.get(index).getPosition());
        return colRef.newColumnExpression();
    }
    
    public ImmutableBitSet getDefaultExtendedColumnRef() {
        return ImmutableBitSet.range(extendedColumnsOffset, mappedColumns.size());
    }
    
    public ImmutableBitSet getExtendedColumnRef(List<RexNode> exprs) {
        if (!hasExtendedColumns()) {
            return ImmutableBitSet.of();
        }
        
        ImmutableBitSet.Builder builder = ImmutableBitSet.builder();
        for (RexNode expr : exprs) {
            builder.addAll(InputFinder.analyze(expr).inputBitSet.build());
        }
        for (int i = 0; i < extendedColumnsOffset; i++) {
            builder.clear(i);
        }
        return builder.build();
    }
   
    public Pair<Integer, Integer> getExtendedColumnReferenceCount(ImmutableBitSet columnRef) {
        Set<String> cf = Sets.newHashSet();
        int columnCount = 0;
        for (int i = extendedColumnsOffset; i < mappedColumns.size(); i++) {
            if (columnRef.get(i)) {
                PColumn dataColumn = ((ProjectedColumn) mappedColumns.get(i))
                        .getSourceColumnRef().getColumn();
                cf.add(dataColumn.getFamilyName().getString());
                columnCount++;
            }
        }
        return new Pair<Integer, Integer>(cf.size(), columnCount);
    }
    
    public PTable createProjectedTable(boolean retainPKColumns) {
        List<ColumnRef> sourceColumnRefs = Lists.<ColumnRef> newArrayList();
        List<PColumn> columns = retainPKColumns ?
                  tableRef.getTable().getColumns() : mappedColumns.subList(0, extendedColumnsOffset);
        for (PColumn column : columns) {
            sourceColumnRefs.add(new ColumnRef(tableRef, column.getPosition()));
        }
        if (extendedColumnsOffset < mappedColumns.size()) {
            for (PColumn column : mappedColumns.subList(extendedColumnsOffset, mappedColumns.size())) {
                sourceColumnRefs.add(new ColumnRef(extendedTableRef, column.getPosition()));
            }
        }
        
        try {
            return TupleProjectionCompiler.createProjectedTable(tableRef, sourceColumnRefs, retainPKColumns);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
    
    public TupleProjector createTupleProjector(boolean retainPKColumns) {
        KeyValueSchemaBuilder builder = new KeyValueSchemaBuilder(0);
        List<Expression> exprs = Lists.<Expression> newArrayList();
        for (int i = 0; i < mappedColumns.size(); i++) {
            if (!SchemaUtil.isPKColumn(mappedColumns.get(i)) || !retainPKColumns) {
                Expression expr = newColumnExpression(i);
                exprs.add(expr);
                builder.addField(expr);
            }
        }
        
        return new TupleProjector(builder.build(), exprs.toArray(new Expression[exprs.size()]));
    }
    
    public RowProjector createRowProjector() {
        List<ColumnProjector> columnProjectors = Lists.<ColumnProjector>newArrayList();
        for (int i = 0; i < mappedColumns.size(); i++) {
            PColumn column = mappedColumns.get(i);
            Expression expr = newColumnExpression(i); // Do not use column.position() here.
            columnProjectors.add(new ExpressionProjector(column.getName().getString(), tableRef.getTable().getName().getString(), expr, false));
        }
        // TODO get estimate row size
        return new RowProjector(columnProjectors, 0, false);        
    }
    
    public void setupScanForExtendedTable(Scan scan, ImmutableBitSet extendedColumnRef,
            PhoenixConnection connection) throws SQLException {
        if (extendedTableRef == null || extendedColumnRef.isEmpty()) {
            return;
        }
        
        TableRef dataTableRef = null;
        List<PColumn> dataColumns = Lists.newArrayList();
        KeyValueSchemaBuilder builder = new KeyValueSchemaBuilder(0);
        List<Expression> exprs = Lists.<Expression> newArrayList();
        for (int i = extendedColumnsOffset; i < mappedColumns.size(); i++) {
            ProjectedColumn column = (ProjectedColumn) mappedColumns.get(i);
            builder.addField(column);
            if (extendedColumnRef.get(i)) {
                dataColumns.add(column.getSourceColumnRef().getColumn());
                exprs.add(column.getSourceColumnRef().newColumnExpression());
                if (dataTableRef == null) {
                    dataTableRef = column.getSourceColumnRef().getTableRef();
                }
            } else {
                exprs.add(LiteralExpression.newConstant(null));
            }
        }
        if (dataColumns.isEmpty()) {
            return;
        }
        
        // Set data columns to be join back from data table.
        serializeDataTableColumnsToJoin(scan, dataColumns);
        // Set tuple projector of the data columns.
        TupleProjector projector = new TupleProjector(builder.build(), exprs.toArray(new Expression[exprs.size()]));
        TupleProjector.serializeProjectorIntoScan(scan, projector, IndexUtil.INDEX_PROJECTOR);
        PTable dataTable = dataTableRef.getTable();
        // Set index maintainer of the local index.
        serializeIndexMaintainerIntoScan(scan, dataTable, connection);
        // Set view constants if exists.
        serializeViewConstantsIntoScan(scan, dataTable);
    }

    private static void serializeDataTableColumnsToJoin(Scan scan, List<PColumn> dataColumns) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            DataOutputStream output = new DataOutputStream(stream);
            WritableUtils.writeVInt(output, dataColumns.size());
            for (PColumn column : dataColumns) {
                Bytes.writeByteArray(output, column.getFamilyName().getBytes());
                Bytes.writeByteArray(output, column.getName().getBytes());
            }
            scan.setAttribute(BaseScannerRegionObserver.DATA_TABLE_COLUMNS_TO_JOIN, stream.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void serializeIndexMaintainerIntoScan(Scan scan, PTable dataTable, PhoenixConnection connection) throws SQLException {
        PName name = getPTable().getName();
        List<PTable> indexes = Lists.newArrayListWithExpectedSize(1);
        for (PTable index : dataTable.getIndexes()) {
            if (index.getName().equals(name) && index.getIndexType() == IndexType.LOCAL) {
                indexes.add(index);
                break;
            }
        }
        ImmutableBytesWritable ptr = new ImmutableBytesWritable();
        IndexMaintainer.serialize(dataTable, ptr, indexes, connection);
        scan.setAttribute(BaseScannerRegionObserver.LOCAL_INDEX_BUILD, ByteUtil.copyKeyBytesIfNecessary(ptr));
        if (dataTable.isTransactional()) {
            scan.setAttribute(BaseScannerRegionObserver.TX_STATE, connection.getMutationState().encodeTransaction());
        }
    }

    private static void serializeViewConstantsIntoScan(Scan scan, PTable dataTable) {
        int dataPosOffset = (dataTable.getBucketNum() != null ? 1 : 0) + (dataTable.isMultiTenant() ? 1 : 0);
        int nViewConstants = 0;
        if (dataTable.getType() == PTableType.VIEW) {
            ImmutableBytesWritable ptr = new ImmutableBytesWritable();
            List<PColumn> dataPkColumns = dataTable.getPKColumns();
            for (int i = dataPosOffset; i < dataPkColumns.size(); i++) {
                PColumn dataPKColumn = dataPkColumns.get(i);
                if (dataPKColumn.getViewConstant() != null) {
                    nViewConstants++;
                }
            }
            if (nViewConstants > 0) {
                byte[][] viewConstants = new byte[nViewConstants][];
                int j = 0;
                for (int i = dataPosOffset; i < dataPkColumns.size(); i++) {
                    PColumn dataPkColumn = dataPkColumns.get(i);
                    if (dataPkColumn.getViewConstant() != null) {
                        if (IndexUtil.getViewConstantValue(dataPkColumn, ptr)) {
                            viewConstants[j++] = ByteUtil.copyKeyBytesIfNecessary(ptr);
                        } else {
                            throw new IllegalStateException();
                        }
                    }
                }
                serializeViewConstantsIntoScan(viewConstants, scan);
            }
        }
    }

    private static void serializeViewConstantsIntoScan(byte[][] viewConstants, Scan scan) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            DataOutputStream output = new DataOutputStream(stream);
            WritableUtils.writeVInt(output, viewConstants.length);
            for (byte[] viewConstant : viewConstants) {
                Bytes.writeByteArray(output, viewConstant);
            }
            scan.setAttribute(BaseScannerRegionObserver.VIEW_CONSTANTS, stream.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    private static List<PColumn> getMappedColumns(PTable pTable) {
        if (pTable.getBucketNum() == null
                && !pTable.isMultiTenant()
                && pTable.getViewIndexId() == null) {
            return pTable.getColumns();
        }
        
        List<PColumn> columns = Lists.newArrayList(pTable.getColumns());
        if (pTable.getViewIndexId() != null) {
            for (Iterator<PColumn> iter = columns.iterator(); iter.hasNext();) {
                if (iter.next().getName().getString().equals(MetaDataUtil.VIEW_INDEX_ID_COLUMN_NAME)) {
                    iter.remove();
                    break;
                }
            }
        }
        if (pTable.isMultiTenant()) {
            columns.remove(pTable.getBucketNum() == null ? 0 : 1);
        }
        if (pTable.getBucketNum() != null) {
            columns.remove(0);
        }
        
        return columns;
    }
}