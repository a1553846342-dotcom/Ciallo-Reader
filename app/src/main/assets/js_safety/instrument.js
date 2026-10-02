(() => {
  const tick = globalThis.__cialloTick;
  const nativeExec = RegExp.prototype.exec;
  const batches = new WeakMap();
  let parsing=false;
  Object.defineProperty(RegExp.prototype,'exec',{configurable:false,writable:false,value:function(input) {
    input=String(input);
    if(parsing) return nativeExec.call(this,input);
    tick();
    if(this.source.length>4096 || input.length>2097152) throw Error('Regex exceeds safety budget');
    // Quantifier characters inside character classes or escaped literals are data.
    // In particular, the common JS escape pattern [.*+?^${}()|[\]\\] is safe,
    // but has different character-class syntax from java.util.regex.Pattern.
    let structural='', inClass=false;
    for(let i=0;i<this.source.length;i++) {
      const c=this.source[i];
      if(c==='\\') {
        const next=this.source[++i];
        if(!inClass) structural+=next && next>='1' && next<='9' ? '\\'+next : 'x';
      } else if(inClass) {
        if(c===']') inClass=false;
      } else if(c==='[') {
        inClass=true; structural+='x';
      } else structural+=c;
    }
    const unsafe = nativeExec.call(/\)[+*{]|\\[1-9]/,structural)!==null || structural.split('+').length+structural.split('*').length>3;
    if(!unsafe) return nativeExec.call(this,input);
    const start=this.global || this.sticky ? this.lastIndex : 0;
    // A global exec loop must not copy the whole HTML across JNI for every
    // match. Cache a bounded batch, while honouring explicit lastIndex changes.
    let batch=batches.get(this);
    if(!batch || batch.input!==input || batch.source!==this.source || batch.flags!==this.flags || batch.next!==start || !batch.items.length) {
      batch={input,source:this.source,flags:this.flags,next:start,
        items:JSON.parse(__boundedRegexBatch(this.source,this.flags,input,start))};
      batches.set(this,batch);
    }
    const result=batch.items.shift();
    if(!result) { if(this.global || this.sticky) this.lastIndex=0; return null; }
    batch.next=result.end;
    const out=result.groups; out.index=result.index; out.input=input;
    if(this.global || this.sticky) this.lastIndex=result.end;
    return out;
  }});
  const insert = (out, at, text, priority=0) => out.push({at,text,priority});
  const instrument = (code) => {
    if (typeof code !== 'string' || code.length > 512000) throw Error('JS script exceeds safety budget');
    let ast;
    try { parsing=true; ast=acorn.parse(code,{ecmaVersion:'latest',allowAwaitOutsideFunction:true,allowReturnOutsideFunction:true}); } finally { parsing=false; }
    const edits = [];
    let nodes=0;
    const visit = (node) => {
      if(!node || typeof node !== 'object') return;
      if(++nodes>100000) throw Error('JS syntax exceeds safety budget');
      if((nodes & 63)===1) tick();
      if(node.type==='Identifier' && node.name==='__cialloTick') throw Error('Reserved script identifier');
      const isLoop=['ForStatement','ForInStatement','ForOfStatement','WhileStatement','DoWhileStatement'].includes(node.type);
      const isFunction=['FunctionDeclaration','FunctionExpression','ArrowFunctionExpression'].includes(node.type);
      if(isLoop || isFunction || node.type==='CatchClause') {
        const body=node.body;
        if(body.type==='BlockStatement') {
          let at=body.start+1;
          if(isFunction) for(const s of body.body) { if(s.directive) at=s.end; else break; }
          insert(edits,at,';__cialloTick();');
        } else if(isFunction) {
          insert(edits,body.start,'(__cialloTick(),(',1); insert(edits,body.end,'))',-1);
        } else {
          insert(edits,body.start,'{__cialloTick();',1); insert(edits,body.end,'}',-1);
        }
      }
      for(const key of Object.keys(node)) {
        const value=node[key];
        if(Array.isArray(value)) {
          for(const item of value) { if(item && item.type) visit(item); }
        } else if(value && typeof value==='object' && value.type) {
          visit(value);
        }
      }
    };
    visit(ast);
    edits.sort((a,b)=>b.at-a.at || a.priority-b.priority);
    for(const edit of edits) code=code.slice(0,edit.at)+edit.text+code.slice(edit.at);
    return code;
  };
  Object.defineProperty(globalThis,'__cialloInstrument',{value:instrument,writable:false,configurable:false});
  const nativeEval=globalThis.eval;
  const wrapConstructor=(sample,prefix) => {
    const proto=Object.getPrototypeOf(sample);
    const Native=proto.constructor;
    const Wrapper=function(...args) {
      const body=String(args.pop() || '');
      const source='('+prefix+'('+args.map(String).join(',')+'){'+body+'})';
      return nativeEval(instrument(source));
    };
    Wrapper.prototype=Native.prototype;
    Object.setPrototypeOf(Wrapper,Native);
    Object.defineProperty(proto,'constructor',{value:Wrapper,writable:false,configurable:false});
    return Wrapper;
  };
  globalThis.Function=wrapConstructor(function(){},'function');
  wrapConstructor(async function(){},'async function');
  wrapConstructor(function*(){},'function*');
  wrapConstructor(async function*(){},'async function*');
  // Site configuration (e.g. hitomi's gg.js) is executed under the same budget
  // and instrumentation as source scripts and dynamic Function constructors.
  globalThis.eval=(code)=>{ tick(); return typeof code==='string' ? nativeEval(instrument(code)) : code; };
})();
