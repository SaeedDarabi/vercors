package vct.col.ast;

public abstract class RecursiveVisitor<T> extends ASTFrame<T> implements
    ASTVisitor<T> {

  public RecursiveVisitor(ASTFrame<T> share) {
    super(share);
    // TODO Auto-generated constructor stub
  }

  public RecursiveVisitor(ProgramUnit source) {
    super(source);
    // TODO Auto-generated constructor stub
  }
  public RecursiveVisitor(ProgramUnit source, ProgramUnit target) {
    super(source, target);
    // TODO Auto-generated constructor stub
  }

  @Override
  public void pre_visit(ASTNode n) {
    enter(n);
  }

  @Override
  public void post_visit(ASTNode n) {
    if(n instanceof BeforeAfterAnnotations){
      BeforeAfterAnnotations baa=(BeforeAfterAnnotations)n;
      enter_before(n);
      dispatch(baa.get_before());
      leave_before(n);
      enter_after(n);
      dispatch(baa.get_after());
      leave_after(n);
    }
    leave(n);
  }

  @Override
  public void visit(StandardProcedure p) {
    // no chidren
  }

  @Override
  public void visit(ConstantExpression e) {
    // no children
  }

  @Override
  public void visit(OperatorExpression e) {
    for(ASTNode arg:e.getArguments()){
      arg.accept(this);
    }    
  }

  @Override
  public void visit(NameExpression e) {
    // no children
  }

  @Override
  public void visit(ClassType t) {
    // no children
  }

  @Override
  public void visit(FunctionType t) {
    t.getResult().accept(this);
    int N=t.getArity();
    for(int i=0;i<N;i++){
      t.getArgument(i).accept(this);
    }
  }
  @Override
  public void visit(TupleType t) {
    for(int i=0;i<t.types.length;i++){
      t.types[i].accept(this);
    }
  }

  @Override
  public void visit(PrimitiveType t) {
    int N=t.getArgCount();
    for(int i=0;i<N;i++){
      t.getArg(i).accept(this);
    }          
  }

  @Override
  public void visit(RecordType t) {
    int N=t.getFieldCount();
    for(int i=0;i<N;i++){
      t.getFieldType(i).accept(this);
    }    
  }

  @Override
  public void visit(MethodInvokation e) {
    // TODO: fix dispatch(e.get_before());
    dispatch(e.object);
    for(ASTNode arg:e.getArgs()){
      arg.accept(this);
    }
    // TODO: fix dispatch(e.get_after());
  }

  private void dispatch(Contract c){
    if (c!=null){
      c.accept(this);
    }
  }
  private void dispatch(ASTNode object) {
    if(object!=null){
      object.accept(this);
    }
  }

  @Override
  public void visit(BlockStatement s) {
    int N=s.getLength();
    for(int i=0;i<N;i++){
      s.getStatement(i).accept(this);
    }
  }

  @Override
  public void visit(IfStatement s) {
    int N=s.getCount();
    for(int i=0;i<N;i++){
      s.getGuard(i).accept(this);
      s.getStatement(i).accept(this);
    }
    
  }

  @Override
  public void visit(ReturnStatement s) {
    dispatch(s.getExpression());
    dispatch(s.get_after());
  }

  @Override
  public void visit(AssignmentStatement s) {
    s.getLocation().accept(this);
    s.getExpression().accept(this);
  }

  @Override
  public void visit(DeclarationStatement s) {
    s.getType().accept(this);
    dispatch(s.getInit());
  }

  @Override
  public void visit(LoopStatement s) {
    dispatch(s.get_before());
    dispatch(s.getInitBlock());
    dispatch(s.getEntryGuard());
    dispatch(s.getUpdateBlock());
    dispatch(s.getContract());
    s.getBody().accept(this);
    dispatch(s.getExitGuard());
    dispatch(s.get_after());
  }

  @Override
  public void visit(Method m) {
    Contract c=m.getContract();
    if (c!=null){
      dispatch(c.pre_condition);
      dispatch(c.post_condition);
    }
    dispatch(m.getBody());
  }

  @Override
  public void visit(ActionBlock ab){
    dispatch(ab.process);
    dispatch(ab.action);
    dispatch(ab.block);
  }
  
  @Override
  public void visit(ASTClass c){
    int N;
    N=c.getStaticCount();
    for(int i=0;i<N;i++){
      c.getStatic(i).accept(this);
    }
    N=c.getDynamicCount();
    for(int i=0;i<N;i++){
      c.getDynamic(i).accept(this);
    }
  }

  @Override
  public void visit(ASTWith astWith) {
    astWith.body.accept(this);
  }

  @Override
  public void visit(BindingExpression e) {
    int N=e.getDeclCount();
    for(int i=0;i<N;i++){
      e.getDeclaration(i).accept(this);
    }
    dispatch(e.result_type);
    dispatch(e.select);
    e.main.accept(this);
  }

  @Override
  public void visit(Dereference e){
    e.object.accept(this);
  }
  
  @Override
  public void visit(Lemma l){
    l.block.accept(this);
  }
  
  public void visit(ParallelBarrier pb){
    dispatch(pb.contract);
  }

  public void visit(ParallelBlock pb){
    dispatch(pb.contract);
    pb.block.accept(this);
  }

  public void visit(Contract c){
    dispatch(c.invariant);
    dispatch(c.pre_condition);
    dispatch(c.post_condition);
  }

  public void visit(ASTSpecial s){
    for(ASTNode n:s.args){
      dispatch(n);
    }
  }
  
  public void visit(ASTSpecialDeclaration s){
    for(ASTNode n:s.args){
      dispatch(n);
    }
  }
  
  @Override
  public void visit(VariableDeclaration decl) {
    dispatch(decl.basetype);
    for(DeclarationStatement d:decl.get()){
      dispatch(d);
    }
  }

  @Override
  public void visit(AxiomaticDataType adt){
    for(Axiom ax:adt.axioms()){
      dispatch(ax);
    }
  }
  
  @Override
  public void visit(Axiom axiom){
    dispatch(axiom.getRule());
  }
  
  @Override
  public void visit(Hole hole){
    dispatch(hole.get());
  }

}
