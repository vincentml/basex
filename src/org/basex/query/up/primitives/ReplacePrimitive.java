package org.basex.query.up.primitives;

import static org.basex.query.QueryText.*;
import static org.basex.query.up.UpdateFunctions.*;
import org.basex.data.Data;
import org.basex.query.QueryException;
import org.basex.query.item.DBNode;
import org.basex.query.item.Nod;
import org.basex.query.item.QNm;
import org.basex.query.item.Type;
import org.basex.query.item.Uri;
import org.basex.query.iter.NodIter;
import org.basex.query.util.Err;

/**
 * Replace primitive.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Lukas Kircher
 */
public final class ReplacePrimitive extends NodeCopy {
  /**
   * Constructor.
   * @param n target node
   * @param replace replace nodes
   */
  public ReplacePrimitive(final Nod n, final NodIter replace) {
    super(n, replace);
  }

  @Override
  public void apply(final int add) {
    final DBNode n = (DBNode) node;
    final int p = n.pre + add;
    final Data d = n.data;
    // source nodes may be empty, thus the replace results in deleting the
    // target node
    if(md == null) {
      d.delete(p);
      return;
    }
    final int par = d.parent(p, Nod.kind(n.type));
    if(n.type == Type.ATT) insertAttributes(p, par, d, md);
    else d.insert(p, par , md);
    d.delete(p + md.meta.size);
    mergeTextNodes(d, p, p + 1);
    mergeTextNodes(d, p - 1, p);
  }

  @Override
  public PrimitiveType type() {
    return PrimitiveType.REPLACENODE;
  }

  @Override
  public void merge(final UpdatePrimitive p) throws QueryException {
    Err.or(UPMULTREPL, node.qname());
  }

  @Override
  public QNm[] addAtt() {
    // [CG] namespace check still buggy (see {@link InsertAttribute}...
    if(node.type != Type.ATT) return null;
    final QNm[] at = new QNm[md.meta.size];
    for(int i = 0; i < md.meta.size; i++) {
      final byte[] nm = md.attName(i);
      final int j = md.ns.uri(nm);
      at[i] = new QNm(md.attName(i));
      if(j != 0) at[i].uri = Uri.uri(md.ns.key(j));
    }
    return at;
  }

  @Override
  public QNm[] remAtt() {
    return node.type != Type.ATT ? new QNm[] { node.qname() } : null;
  }
}
